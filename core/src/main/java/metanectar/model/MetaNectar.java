package metanectar.model;

import com.cloudbees.commons.metanectar.agent.AgentListener;
import com.cloudbees.commons.metanectar.agent.AgentStatusListener;
import com.cloudbees.commons.metanectar.agent.MetaNectarAgentProtocol;
import com.cloudbees.commons.metanectar.agent.MetaNectarAgentProtocol.GracefulConnectionRefusalException;
import hudson.Extension;
import hudson.PluginManager;
import hudson.model.*;
import hudson.remoting.Channel;
import hudson.util.AdministrativeError;
import hudson.util.AlternativeUiTextProvider;
import hudson.util.FormValidation;
import hudson.views.StatusColumn;
import metanectar.Config;
import metanectar.model.views.MasterServerColumn;
import metanectar.provisioning.MasterProvisioner;
import metanectar.provisioning.MasterProvisioningNodeProperty;
import metanectar.provisioning.MasterProvisioningService;
import org.jenkinsci.main.modules.instance_identity.InstanceIdentity;
import org.jvnet.hudson.reactor.ReactorException;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.File;
import java.io.IOException;
import java.net.BindException;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static hudson.Util.fixEmpty;

/**
 * The root object of MetaNectar.
 *
 * Extends from {@link Hudson} to keep existing code working.
 *
 * @author Paul Sandoz
 * @author Kohsuke Kawaguchi
 */
public class MetaNectar extends Hudson {
    private static final Logger LOGGER = Logger.getLogger(MetaNectar.class.getName());

    private transient AgentListener nectarAgentListener;

    private transient Config config;

    public transient final MasterProvisioner masterProvisioner;

    public static class AgentProtocolListener extends MetaNectarAgentProtocol.Listener {
        private final MetaNectar metaNectar;

        public AgentProtocolListener(MetaNectar metaNectar) {
            this.metaNectar = metaNectar;
        }

        public URL getEndpoint() throws IOException {
            return new URL(metaNectar.getRootUrl());
        }

        public void onConnectingTo(URL address, X509Certificate identity, String organization, Map<String, String> properties) throws GeneralSecurityException, IOException {
            final MasterServer server = metaNectar.getMasterByOrganization(organization);

            if (server == null) {
                throw new IllegalStateException("The master " + organization + " does not exist");
            }

            if (!server.isApprovable()) {
                throw new IllegalStateException("The master " + organization + " is not in an approvable state: " + server.getState().name());
            }

            if (server.isApproved()) {
                if (server.getIdentity().equals(identity.getPublicKey())) {
                    server.setReapprovedState();
                    LOGGER.info("Master is identified and approved: " + organization + " " + address);
                    return;
                }

                throw new GeneralSecurityException("The master " + organization + " identity does not match that which was previously approved");
            }

            if (properties.containsKey(MasterProvisioningService.PROPERTY_PROVISION_GRANT_ID)) {
                // Check if there is a grant for automatic registration
                final String receivedGrant = properties.get(MasterProvisioningService.PROPERTY_PROVISION_GRANT_ID);

                if (server.getGrantId().equals(receivedGrant)) {
                    server.setApprovedState((RSAPublicKey) identity.getPublicKey(), address);
                    LOGGER.info("Valid grant received. Master is identified and approved: " + organization + " " + address);
                    return;
                }

                GeneralSecurityException e = new GeneralSecurityException("Invalid grant for master " + organization + ": received " + receivedGrant + " expected " + server.getGrantId());
                server.setApprovalErrorState(e);
                throw e;
            }

            LOGGER.info("Master is not approved: "+ organization + " " + address);
            GracefulConnectionRefusalException e = new GracefulConnectionRefusalException("This master is not approved by MetaNectar");
            server.setApprovalErrorState(e);
            throw e;
        }

        public void onConnectedTo(Channel channel, X509Certificate identity, String organization) throws IOException {
            final MasterServer server = metaNectar.getMasterByOrganization(organization);

            if (server != null) {
                server.setConnectedState(channel);
                return;
            }

            channel.close();
            throw new IOException("Unable to route the connection. No server found");
        }

        @Override
        public void onRefusal(GracefulConnectionRefusalException e) throws Exception {
            throw e;
        }

        @Override
        public void onError(Exception e) throws Exception {
            throw e;
        }
    }

    public MetaNectar(File root, ServletContext context) throws IOException, InterruptedException, ReactorException {
        this(root, context, null);
    }

    public MetaNectar(File root, ServletContext context, PluginManager pluginManager) throws IOException, InterruptedException, ReactorException {
        super(root, context, pluginManager);

        this.masterProvisioner = new MasterProvisioner(this);

        configureNectarAgentListener(new AgentProtocolListener(this));

        this.config = Config.getInstance();

        if (!getConfig().isMasterProvisioning()) {
            // If master provisioning is disabled then remove the master provisioning node property if present
            MasterProvisioningNodeProperty p = getGlobalNodeProperties().get(MasterProvisioningNodeProperty.class);
            if (p != null) {
                 getGlobalNodeProperties().remove(p);
            }
        }
    }

    public void configureNectarAgentListener(MetaNectarAgentProtocol.Listener l) throws IOException {
        if (nectarAgentListener != null)
            nectarAgentListener.shutdown();

        AgentStatusListener asl = new AgentStatusListener.LoggerListener(LOGGER);

        InstanceIdentity id = InstanceIdentity.get();

        MetaNectarAgentProtocol.Inbound p = new MetaNectarAgentProtocol.Inbound(
                MetaNectarAgentProtocol.getInstanceIdentityCertificate(id, this),
                id.getPrivate(),
                "MetaNectar",
                Collections.<String, String>emptyMap(),
                l,
                null);

        try {
            nectarAgentListener = new AgentListener(asl, 0, Collections.singletonList(p));
            new Thread(nectarAgentListener, "MetaNectar agent listener port=" + nectarAgentListener.getPort()).start();
        } catch (BindException e) {
            new AdministrativeError(getClass().getName()+".tcpBind",
                    "Failed to listen to incoming agent connection",
                    "Failed to listen to incoming agent connection. <a href='configure'>Change the port number</a> to solve the problem.",e);
        }
    }

    private String rootUrl;

    @Override
    public String getRootUrl() {
        if (rootUrl != null) {
            return rootUrl;
        }

        try {
            URL u = Config.getInstance().getEndpoint();
            rootUrl = u.toExternalForm();
        } catch (Exception ex) {}

        // TODO Hudson.getRootUrl() is returning null
        return super.getRootUrl();
    }

    public void setRootUrl(String rootUrl) {
        this.rootUrl = rootUrl;
    }

    public URL getMetaNectarPortUrl() {
        try {
            String rootUrl = getRootUrl();
            if (!rootUrl.endsWith("/"))
                rootUrl += "/";

            return new URL(rootUrl + MetaNectarPortRootAction.URL_NAME + "/");
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    public AgentListener getNectarAgentListener() {
        return nectarAgentListener;
    }

    @Override
    public void cleanUp() {
        super.cleanUp();

        if(nectarAgentListener != null)
            nectarAgentListener.shutdown();
    }

    @Override
    public String getDisplayName() {
        return Messages.MetaNectar_DisplayName();
    }

    public List<MasterServer> getMasters() {
        return getItems(MasterServer.class);
    }

    public MasterServer getMasterByIdentity(PublicKey identity) {
        for (MasterServer js : getMasters())
            if (js.getIdentity().equals(identity))
                return js;
        return null;
    }

    public MasterServer getMasterByOrganization(String organization) {
        return (MasterServer)getItem(organization);
    }

    public void setConfig(Config config) {
        this.config = config;
    }

    public Config getConfig() {
        return config;
    }

    /**
     * Sets up the initial view state.
     */
    @Override
    protected View createInitialView() {
        try {
            MasterServerListView lv = new MasterServerListView("All");
            lv.setColumns(Arrays.asList(
                    new StatusColumn(),
                    new MasterServerColumn()));
            return lv;
        } catch (IOException e) {
            // view doesn't save itself unless it's connected to the parent, which we don't do in this method.
            // so this never happens
            throw new AssertionError(e);
        }
    }

    public MasterServer createMasterServer(String organization) throws IOException {
        checkOrganizationName(organization);

        final MasterServer server = createProject(MasterServer.class, organization);

        server.setCreatedState();
        return server;
    }

    private void checkOrganizationName(String name) {
        checkPermission(Item.CREATE);

        checkGoodName(name);
        name = name.trim();
        if (getItem(name) != null)
            throw new Failure("Organization " + name + "already exists");
    }

    @Override
    public synchronized void doConfigSubmit(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException, Descriptor.FormException {
        super.doConfigSubmit(req, rsp);
        // Override and trim the labels in case master provisioning on MetaNectar configuration was added or removed.
        trimLabels();
    }

    public FormValidation doCheckOrganizationName(@QueryParameter String value) {
        // this method can be used to check if a file exists anywhere in the file system,
        // so it should be protected.
        checkPermission(Item.CREATE);

        if(fixEmpty(value)==null)
            return FormValidation.ok();

        try {
            checkOrganizationName(value);

            return FormValidation.ok();
        } catch (Failure e) {
            return FormValidation.error(e.getMessage());
        }
    }

    /**
     * Provision a new master and issue a grant for automatic approval.
     *
     */
    public MasterServer doProvisionMasterServer(String organization) throws IOException {
        final MasterServer server = createMasterServer(organization);

        server.provisionAndStartAction();

        return server;
    }

    /**
     * Attach to a new master and issue a grant for automatic approval.
     *
     */
    public MasterServer doAttachMasterServer(String organization) throws IOException {
        final MasterServer server = createMasterServer(organization);

        return server;
    }

    //

    public static MetaNectar getInstance() {
        return (MetaNectar)Hudson.getInstance();
    }

    @Extension
    public static class PronounProvider extends AlternativeUiTextProvider {

        @Override
        public <T> String getText( Message<T> text, T context )
        {
            if (context instanceof MasterServer) {
                if (AbstractItem.PRONOUN.equals( text )) {
                    return Messages.Master_Pronoun();
                }
            }
            return null;
        }
    }
}