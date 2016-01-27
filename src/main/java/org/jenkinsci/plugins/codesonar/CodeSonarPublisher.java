package org.jenkinsci.plugins.codesonar;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.AbortException;
import hudson.DescriptorExtensionList;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.ItemGroup;
import hudson.model.Result;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.apache.commons.collections.ListUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.client.utils.URIBuilder;
import org.javatuples.Pair;
import org.jenkinsci.plugins.codesonar.Utils.UrlFilters;
import org.jenkinsci.plugins.codesonar.conditions.Condition;
import org.jenkinsci.plugins.codesonar.conditions.ConditionDescriptor;
import org.jenkinsci.plugins.codesonar.models.CodeSonarBuildActionDTO;
import org.jenkinsci.plugins.codesonar.models.analysis.Analysis;
import org.jenkinsci.plugins.codesonar.models.metrics.Metrics;
import org.jenkinsci.plugins.codesonar.models.procedures.Procedures;
import org.jenkinsci.plugins.codesonar.services.AnalysisService;
import org.jenkinsci.plugins.codesonar.services.HttpService;
import org.jenkinsci.plugins.codesonar.services.MetricsService;
import org.jenkinsci.plugins.codesonar.services.ProceduresService;
import org.jenkinsci.plugins.codesonar.services.XmlSerializationService;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 *
 * @author andrius
 */
public class CodeSonarPublisher extends Recorder {

    private String hubAddress;
    private String projectName;
    private String protocol;
    private String hubPort;

    private XmlSerializationService xmlSerializationService;
    private HttpService httpService;
    private AnalysisService analysisService;
    private MetricsService metricsService;
    private ProceduresService proceduresService;

    private List<Condition> conditions;

    private String credentialId;

    @DataBoundConstructor
    public CodeSonarPublisher(List<Condition> conditions, String protocol, String hubAddress, String hubPort, String projectName, String credentialId) {
        xmlSerializationService = new XmlSerializationService();
        httpService = new HttpService();
        analysisService = new AnalysisService(httpService, xmlSerializationService);
        metricsService = new MetricsService(httpService, xmlSerializationService);
        proceduresService = new ProceduresService(httpService, xmlSerializationService);

        this.hubAddress = hubAddress;
        this.projectName = projectName;
        this.protocol = protocol;
        this.hubPort = hubPort;

        if (conditions == null) {
            conditions = ListUtils.EMPTY_LIST;
        }
        this.conditions = conditions;

        this.credentialId = credentialId;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException, AbortException {
        String expandedHubAddress = build.getEnvironment(listener).expand(Util.fixNull(hubAddress));
        String expandedProjectName = build.getEnvironment(listener).expand(Util.fixNull(projectName));
        String expandedHubPort = build.getEnvironment(listener).expand(Util.fixNull(getHubPort()));

        if (expandedHubAddress.isEmpty()) {
            throw new AbortException("Hub address not provided");
        }
        if (expandedHubPort.isEmpty()) {
            throw new AbortException("Hub port not provided");
        }
        if (expandedProjectName.isEmpty()) {
            throw new AbortException("Project name not provided");
        }

        URIBuilder uriBuilder = new URIBuilder();
        uriBuilder.setScheme(getProtocol());
        uriBuilder.setHost(expandedHubAddress);
        uriBuilder.setPort(Integer.parseInt(expandedHubPort));
        URI baseHubUri;
        try {
            baseHubUri = uriBuilder.build();
        } catch (URISyntaxException ex) {
            throw new AbortException(String.format("[CodeSonar] %s", ex.getMessage()));
        }

        UsernamePasswordCredentials credentials = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(UsernamePasswordCredentials.class, build.getParent(), ACL.SYSTEM,
                        Collections.<DomainRequirement>emptyList()), CredentialsMatchers.withId(credentialId));

        if (credentials != null) {
            httpService.authenticate(baseHubUri,
                    credentials.getUsername(),
                    credentials.getPassword().getPlainText());
        }

        List<String> logFile = IOUtils.readLines(build.getLogReader());
        String analysisUrl = analysisService.getAnalysisUrlFromLogFile(logFile);

        if (analysisUrl == null) {
            analysisUrl = analysisService.getLatestAnalysisUrlForAProject(baseHubUri, expandedProjectName);
        }

        Analysis analysisActiveWarnings = analysisService.getAnalysisFromUrl(analysisUrl, UrlFilters.ACTIVE);

        String metricsUrl = metricsService.getMetricsUrlFromAnAnalysisId(baseHubUri.toString(), analysisActiveWarnings.getAnalysisId());
        Metrics metrics = metricsService.getMetricsFromUrl(metricsUrl);

        String proceduresUrl = proceduresService.getProceduresUrlFromAnAnalysisId(baseHubUri.toString(), analysisActiveWarnings.getAnalysisId());
        Procedures procedures = proceduresService.getProceduresFromUrl(proceduresUrl);

        Analysis analysisNewWarnings = analysisService.getAnalysisFromUrl(analysisUrl, UrlFilters.NEW);

        List<Pair<String, String>> conditionNamesAndResults = new ArrayList<Pair<String, String>>();

        CodeSonarBuildActionDTO buildActionDTO = new CodeSonarBuildActionDTO(analysisActiveWarnings,
                analysisNewWarnings, metrics, procedures, baseHubUri.toString());

        build.addAction(new CodeSonarBuildAction(buildActionDTO, build));

        for (Condition condition : conditions) {
            Result validationResult = condition.validate(build, launcher, listener);

            Pair<String, String> pair = Pair.with(condition.getDescriptor().getDisplayName(), validationResult.toString());
            conditionNamesAndResults.add(pair);

            build.setResult(validationResult);
            listener.getLogger().println(String.format(("'%s' marked the build as %s"), condition.getDescriptor().getDisplayName(), validationResult.toString()));
        }

        build.getAction(CodeSonarBuildAction.class).getBuildActionDTO()
                .setConditionNamesAndResults(conditionNamesAndResults);

        return true;
    }

    @Override
    public Collection<? extends Action> getProjectActions(AbstractProject<?, ?> project) {
        return Arrays.asList(
                new CodeSonarProjectAction(project),
                new CodeSonarLatestAnalysisProjectAction(project)
        );
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }

    @Override
    public BuildStepDescriptor getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    /**
     * @return the hubAddress
     */
    public String getHubAddress() {
        return hubAddress;
    }

    /**
     * @return the hubPort
     */
    public String getHubPort() {
        return hubPort;
    }

    /**
     * @return the protocol
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * @param protocol the protocol to set
     */
    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    /**
     * @param hubPort the hubPort to set
     */
    public void setHubPort(String hubPort) {
        this.hubPort = hubPort;
    }

    /**
     * @param hubAddress the hubAddress to set
     */
    public void setHubAddress(String hubAddress) {
        this.hubAddress = hubAddress;
    }

    /**
     * @return the projectLocation
     */
    public String getProjectName() {
        return projectName;
    }

    /**
     * @param projectName the projectLocation to set
     */
    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public void setXmlSerializationService(XmlSerializationService xmlSerializationService) {
        this.xmlSerializationService = xmlSerializationService;
    }

    public void setHttpService(HttpService httpService) {
        this.httpService = httpService;
    }

    public void setAnalysisService(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    public void setMetricsService(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    public void setProceduresService(ProceduresService proceduresService) {
        this.proceduresService = proceduresService;
    }

    public String getCredentialId() {
        return credentialId;
    }

    public void setCredentialId(String credentialId) {
        this.credentialId = credentialId;
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        public DescriptorImpl() {
            load();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> type) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Codesonar";
        }

        public List<ConditionDescriptor<?>> getAllConditions() {
            DescriptorExtensionList<Condition, ConditionDescriptor<Condition>> all = Condition.getAll();

            List<ConditionDescriptor<?>> list = new ArrayList<ConditionDescriptor<?>>();
            for (ConditionDescriptor<?> d : all) {
                list.add(d);
            }

            return list;
        }

        public FormValidation doCheckHubAddress(@QueryParameter("hubAddress") String hubAddress) {
            if (!StringUtils.isBlank(hubAddress)) {
                return FormValidation.ok();
            }
            return FormValidation.error("Hub address cannot be empty.");
        }

        public FormValidation doCheckHubPort(@QueryParameter("hubPort") String hubPort) {
            if (!StringUtils.isBlank(hubPort)) {
                return FormValidation.ok();
            }
            return FormValidation.error("Hub port cannot be empty.");
        }

        public FormValidation doCheckProjectName(@QueryParameter("projectName") String projectName) {
            if (!StringUtils.isBlank(projectName)) {
                return FormValidation.ok();
            }
            return FormValidation.error("Project name cannot be empty.");
        }

        public ListBoxModel doFillCredentialIdItems(final @AncestorInPath ItemGroup<?> context) {
            final List<StandardUsernameCredentials> credentials = CredentialsProvider.lookupCredentials(StandardUsernameCredentials.class, context, ACL.SYSTEM, Collections.<DomainRequirement>emptyList());

            StandardListBoxModel model2 = (StandardListBoxModel) new StandardListBoxModel().withEmptySelection().withMatching(new CredentialsMatcher() {
                @Override
                public boolean matches(Credentials item) {
                    return item instanceof UsernamePasswordCredentials;
                }
            }, credentials);

            return model2;
        }

        public ListBoxModel doFillProtocolItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("http", "http");
            items.add("https", "https");
            return items;
        }
    }

}
