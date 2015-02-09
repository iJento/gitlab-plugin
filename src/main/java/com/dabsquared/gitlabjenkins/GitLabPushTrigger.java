package com.dabsquared.gitlabjenkins;

import com.google.common.base.Strings;
import hudson.Extension;
import hudson.Util;
import hudson.model.*;
import hudson.plugins.git.RevisionParameterAction;
import hudson.plugins.git.GitSCM;
import hudson.scm.SCM;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.FormValidation;
import hudson.util.SequentialExecutionQueue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.gitlab.api.models.GitlabBranch;
import org.gitlab.api.models.GitlabProject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Triggers a build when we receive a GitLab WebHook.
 *
 * @author Daniel Brooks
 */
public class GitLabPushTrigger extends Trigger<AbstractProject<?, ?>> {
    private static final Logger LOGGER = Logger.getLogger(GitLabPushTrigger.class.getName());
    private boolean triggerOnPush = true;
    private boolean triggerOnMergeRequest = true;
    private boolean triggerOpenMergeRequestOnPush = true;
    private boolean setBuildDescription = true;
    private boolean addNoteOnMergeRequest = true;
    private boolean filterRequests = false;
    private String commitMessageFilterStrings;
    private boolean allowAllBranches = false;

    private List<String> allowedBranches;

    // compatibility with earlier plugins
    public Object readResolve() {
        if (null == allowedBranches) {
            allowedBranches = new ArrayList<String>();
        }
        return this;
    }

    @DataBoundConstructor
    public GitLabPushTrigger(boolean triggerOnPush, boolean triggerOnMergeRequest, boolean triggerOpenMergeRequestOnPush, boolean setBuildDescription, boolean allowAllBranches, List<String> allowedBranches,
                             boolean filterRequests, String commitMessageFilterStrings) {
        this.triggerOnPush = triggerOnPush;
        this.triggerOnMergeRequest = triggerOnMergeRequest;
        this.triggerOpenMergeRequestOnPush = triggerOpenMergeRequestOnPush;
        this.setBuildDescription = setBuildDescription;
        this.allowAllBranches = allowAllBranches;
        this.allowedBranches = allowedBranches;
        this.commitMessageFilterStrings = commitMessageFilterStrings;
        this.filterRequests = filterRequests || Strings.isNullOrEmpty(this.commitMessageFilterStrings);
    }


    public boolean getTriggerOnPush() {
        return triggerOnPush;
    }

    public boolean getTriggerOnMergeRequest() {
        return triggerOnMergeRequest;
    }

    public boolean getTriggerOpenMergeRequestOnPush() {
        return triggerOpenMergeRequestOnPush;
    }

    public boolean getSetBuildDescription() {
        return setBuildDescription;
    }

    public boolean getAddNoteOnMergeRequest() {
        return addNoteOnMergeRequest;
    }

    public List<String> getAllowedBranches() {
        return allowedBranches;
    }
    
    public String getCommitMessageFilterStrings() {
        return commitMessageFilterStrings;
    }

    public boolean getFilterRequests() {
        return filterRequests;
    }

    public boolean getAllowAllBranches() {
        return allowAllBranches;
    }

    public void onPost(final GitLabPushRequest req) {
    	boolean allowBuild = allowAllBranches || (allowedBranches.isEmpty() || allowedBranches.contains(getSourceBranch(req)));

        LOGGER.log(Level.FINEST, "allowAllBranches  || allowedBranches.isEmpty() || allowedBranches.contains(getSourceBranch"
                      + "(req)) > {0} || {1} || {2} = {3}",
                new Object[]{allowAllBranches , allowedBranches.isEmpty(), allowedBranches.contains(getSourceBranch(req)),
                        allowAllBranches  || allowedBranches.isEmpty() || allowedBranches.contains(getSourceBranch(req))});

        LOGGER.log(Level.INFO, "triggerOnPush && (allowBuild) && !pushRequestContainsExcludedCommitMessages(req) > {0} "
                + "&& {1} && {2} = {3}",new Object[]{triggerOnPush, allowBuild, !pushRequestContainsExcludedCommitMessages(req),
                (triggerOnPush && (allowBuild) && !pushRequestContainsExcludedCommitMessages(req))});


        if (triggerOnPush && (allowBuild) && !pushRequestContainsExcludedCommitMessages(req)) {
            getDescriptor().queue.execute(new Runnable() {

                public void run() {
                    LOGGER.log(Level.INFO, "{0} triggered.", job.getName());
                    String name = " #" + job.getNextBuildNumber();
                    LOGGER.fine("Next build " + name);
                    GitLabPushCause cause = createGitLabPushCause(req);
                    LOGGER.fine("Cause: " + cause.getShortDescription());
                    Action[] actions = createActions(req);
                    LOGGER.fine("Actions: ");
                    for (Action action : actions) {
                        LOGGER.fine("Action: "+action.getDisplayName()+", "+action.getUrlName()+", "+action.toString());
                    }
                    if (job.scheduleBuild(job.getQuietPeriod(), cause, actions)) {
                        LOGGER.log(Level.INFO, "GitLab Push Request detected in {0}. Triggering {1}", new String[]{job.getName(), name});
                    } else {
                        LOGGER.log(Level.INFO, "GitLab Push Request detected in {0}. Job is already in the queue.", job.getName());
                    }
                }

                private GitLabPushCause createGitLabPushCause(GitLabPushRequest req) {
                    GitLabPushCause cause;
                    StringBuilder causeString = new StringBuilder("branch ")
                          .append(getSourceBranch(req)).append(": ");

                    if (req.getCommits().size() == 0) {
                        causeString.append("branch created");
                    } else {
                        causeString.append("by user");
                       String user = req.getUser_name();
                        if (user != null) {
                            causeString.append(" ").append(user);
                        }
                    }
                    try {
                        cause = new GitLabPushCause(causeString.toString(), getLogFile());
                    } catch (IOException ex) {
                        cause = new GitLabPushCause(causeString.toString());
                    }
                    return cause;
                }

                private Action[] createActions(GitLabPushRequest req) {
                    ArrayList<Action> actions = new ArrayList<Action>();

                    String branch = getSourceBranch(req);

                    LOGGER.log(Level.INFO, "GitLab Push Request from branch {0}.", branch);

                    Map<String, ParameterValue> values = new HashMap<String, ParameterValue>();
                    values.put("gitlabSourceBranch", new StringParameterValue("gitlabSourceBranch", branch));
                    values.put("gitlabTargetBranch", new StringParameterValue("gitlabTargetBranch", branch));
                    values.put("gitlabBranch", new StringParameterValue("gitlabBranch", branch));
                    values.put("gitlabSourceRepoName", new StringParameterValue("gitlabSourceRepoName", getDesc().getSourceRepoNameDefault()));
                    values.put("gitlabSourceRepoURL", new StringParameterValue("gitlabSourceRepoURL", getDesc().getSourceRepoURLDefault().toString()));

                    List<ParameterValue> listValues = new ArrayList<ParameterValue>(values.values());

                    ParametersAction parametersAction = new ParametersAction(listValues);
                    actions.add(parametersAction);

                    RevisionParameterAction revision = new RevisionParameterAction(req.getLastCommit().getId());
                    actions.add(revision);
                    Action[] actionsArray = actions.toArray(new Action[0]);

                    return actionsArray;
                }
            });
        }
    }

    public void onPost(final GitLabMergeRequest req) {
        LOGGER.log(Level.INFO,"Posted merge request");
        if (triggerOnMergeRequest) {
            getDescriptor().queue.execute(new Runnable() {
                public void run() {
                    LOGGER.log(Level.INFO, "{0} triggered.", job.getName());
                    String name = " #" + job.getNextBuildNumber();
                    LOGGER.fine("Next build " + name);
                    GitLabMergeCause cause = createGitLabMergeCause(req);
                    LOGGER.fine("Cause " + cause.getShortDescription());
                    Action[] actions = createActions(req);
                    LOGGER.fine("Actions: ");
                    for (Action action : actions) {
                        LOGGER.fine("Action: "+action.getDisplayName()+", "+action.getUrlName()+", "+action.toString());
                    }
                    if (job.scheduleBuild(job.getQuietPeriod(), cause, actions)) {
                        LOGGER.log(Level.INFO, "GitLab Merge Request detected in {0}. Triggering {1}", new String[]{job.getName(), name});
                    } else {
                        LOGGER.log(Level.INFO, "GitLab Merge Request detected in {0}. Job is already in the queue.", job.getName());
                    }
                }

                private GitLabMergeCause createGitLabMergeCause(GitLabMergeRequest req) {
                    GitLabMergeCause cause;
                    try {
                        cause = new GitLabMergeCause(req, getLogFile());
                    } catch (IOException ex) {
                        cause = new GitLabMergeCause(req);
                    }
                    return cause;
                }

                private Action[] createActions(GitLabMergeRequest req) {
                    List<Action> actions = new ArrayList<Action>();

                    Map<String, ParameterValue> values = new HashMap<String, ParameterValue>();
                    values.put("gitlabSourceBranch", new StringParameterValue("gitlabSourceBranch", getSourceBranch(req)));
                    values.put("gitlabTargetBranch", new StringParameterValue("gitlabTargetBranch", req.getObjectAttribute().getTargetBranch()));

                    String sourceRepoName = getDesc().getSourceRepoNameDefault();
                    String sourceRepoURL = getDesc().getSourceRepoURLDefault().toString();

                    if (!getDescriptor().getGitlabHostUrl().isEmpty()) {
                        // Get source repository if communication to Gitlab is possible
                        try {
                            sourceRepoName = req.getSourceProject(getDesc().getGitlab()).getPathWithNamespace();
                            sourceRepoURL = req.getSourceProject(getDesc().getGitlab()).getSshUrl();
                        } catch (IOException ex) {
                            LOGGER.log(Level.WARNING, "Could not fetch source project''s data from Gitlab. '('{0}':' {1}')'", new String[]{ex.toString(), ex.getMessage()});
                        }
                    }

                    values.put("gitlabSourceRepoName", new StringParameterValue("gitlabSourceRepoName", sourceRepoName));
                    values.put("gitlabSourceRepoURL", new StringParameterValue("gitlabSourceRepoURL", sourceRepoURL));

                    List<ParameterValue> listValues = new ArrayList<ParameterValue>(values.values());

                    ParametersAction parametersAction = new ParametersAction(listValues);
                    actions.add(parametersAction);

                    Action[] actionsArray = actions.toArray(new Action[0]);

                    return actionsArray;
                }


            });
        }
    }

    protected boolean pushRequestContainsExcludedCommitMessages(GitLabPushRequest pushRequest) {
        if (!filterRequests || commitMessageFilterStrings.isEmpty()) return false;
        if (!getSourceBranch(pushRequest).equalsIgnoreCase("master")) return false; // We only want to filter the master branch
        for (GitLabPushRequest.Commit commit : pushRequest.getCommits()) {
            String msg = commit.getMessage();
            for (String filter : commitMessageFilterStrings.split("[\t\r\n]")) {
                if (msg.contains(filter)) {
                    LOGGER.log(Level.INFO, "Excluding push request as it has a commit with a message containing: " + filter);
                    return true;
                }
            }
        }
        return false;
    }

    private void setBuildCauseInJob(AbstractBuild abstractBuild) {
        if (setBuildDescription) {
            Cause pcause = abstractBuild.getCause(GitLabPushCause.class);
            Cause mcause = abstractBuild.getCause(GitLabMergeCause.class);
            String desc = null;
            if (pcause != null) desc = pcause.getShortDescription();
            if (mcause != null) desc = mcause.getShortDescription();
            if (desc != null && desc.length() > 0) {
                try {
                    abstractBuild.setDescription(desc);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void onCompleted(AbstractBuild build) {
        Cause mCause = build.getCause(GitLabMergeCause.class);
        if (mCause != null && mCause instanceof GitLabMergeCause) {
            onCompleteMergeRequest(build, (GitLabMergeCause) mCause);
        }

    }

    private void onCompleteMergeRequest(AbstractBuild abstractBuild, GitLabMergeCause cause) {
        if (addNoteOnMergeRequest) {
            StringBuilder msg = new StringBuilder();
            if (abstractBuild.getResult() == Result.SUCCESS) {
                msg.append(":white_check_mark:");
            } else {
                msg.append(":anguished:");
            }
            msg.append(" Jenkins Build ").append(abstractBuild.getResult().color.getDescription());
            String buildUrl = Jenkins.getInstance().getRootUrl() + abstractBuild.getUrl();
            msg.append("\n\nResults available at: ")
                    .append("[").append("Jenkins").append("](").append(buildUrl).append(")");
            try {
                GitlabProject proj = new GitlabProject();
                proj.setId(cause.getMergeRequest().getObjectAttribute().getTargetProjectId());
                org.gitlab.api.models.GitlabMergeRequest mr = this.getDescriptor().getGitlab().instance().getMergeRequest(proj, cause.getMergeRequest().getObjectAttribute().getId());
                this.getDescriptor().getGitlab().instance().createNote(mr, msg.toString());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    public void onStarted(AbstractBuild abstractBuild) {
        setBuildCauseInJob(abstractBuild);
    }

    private String getSourceBranch(GitLabRequest req) {
        String result = null;
        if (req instanceof GitLabPushRequest) {
            result = ((GitLabPushRequest) req).getRef().replaceAll("refs/heads/", "");
        } else {
            result = ((GitLabMergeRequest) req).getObjectAttribute().getSourceBranch();
        }

        return result;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DescriptorImpl.get();
    }

    public static DescriptorImpl getDesc() {
        return DescriptorImpl.get();
    }

    public File getLogFile() {
        return new File(job.getRootDir(), "gitlab-polling.log");
    }

    @Extension
    public static class DescriptorImpl extends TriggerDescriptor {

        AbstractProject project;
        private String gitlabApiToken;
        private String gitlabHostUrl = "";
        private boolean ignoreCertificateErrors = false;
        private List<String> projectBranches = null;

        private transient final SequentialExecutionQueue queue = new SequentialExecutionQueue(Jenkins.MasterComputer.threadPoolForRemoting);
        private transient GitLab gitlab;

        public DescriptorImpl() {
            load();
        }

        @Override
        public boolean isApplicable(Item item) {
            if (item instanceof AbstractProject) {
                project = (AbstractProject) item;
                return true;
            } else {
                return false;
            }
        }

        @Override
        public String getDisplayName() {
            if (project == null) {
                return "Build when a change is pushed to GitLab, unknown URL";
            }

            final List<String> projectParentsUrl = new ArrayList<String>();

            try {
                for (Object parent = project.getParent(); parent instanceof Item; parent = ((Item) parent)
                      .getParent()) {
                    projectParentsUrl.add(0, ((Item) parent).getName());
                }
            } catch (IllegalStateException e) {
                return "Build when a change is pushed to GitLab, unknown URL";
            }
            final StringBuilder projectUrl = new StringBuilder();
            projectUrl.append(Jenkins.getInstance().getRootUrl());
            projectUrl.append(GitLabWebHook.WEBHOOK_URL);
            projectUrl.append('/');
            for (final String parentUrl : projectParentsUrl) {
                projectUrl.append(Util.rawEncode(parentUrl));
                projectUrl.append('/');
            }
            projectUrl.append(Util.rawEncode(project.getName()));

            return "Build when a change is pushed to GitLab. GitLab CI Service URL: " + projectUrl;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            gitlabApiToken = formData.getString("gitlabApiToken");
            gitlabHostUrl = formData.getString("gitlabHostUrl");
            ignoreCertificateErrors = formData.getBoolean("ignoreCertificateErrors");
            save();
            gitlab = new GitLab();
            return super.configure(req, formData);
        }

        public List<String> getProjectBranches() {
            projectBranches = new ArrayList<String>();
            try {
                /* TODO until java-gitlab-api v1.1.5 is released,
            		cannot search projects by namespace/name
            		For now getting project id before getting project branches
            	 */
                URIish sourceRepository = getSourceRepoURLDefault();
                if (!gitlabHostUrl.isEmpty() && (null != sourceRepository)) {
                    List<GitlabProject> projects = getGitlab().instance().getProjects();
                    for (GitlabProject project : projects) {
                        if (project.getSshUrl().equalsIgnoreCase(sourceRepository.toString()) ||
                                project.getHttpUrl().equalsIgnoreCase(sourceRepository.toString())) {
                            //Get all branches of project
                            List<GitlabBranch> branches = getGitlab().instance().getBranches(project);
                            for (GitlabBranch branch : branches) {
                                projectBranches.add(branch.getName());
                            }
                            break;
                        }
                    }
                }
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Could not fetch source project''s data from Gitlab. '('{0}':' {1}')'", new String[]{ex.toString(), ex.getMessage()});
            }

            return projectBranches;
        }

        /**
         * Get the URL of the first declared repository in the project configuration.
         * Use this as default source repository url.
         *
         * @return URIish the default value of the source repository url
         */
        protected URIish getSourceRepoURLDefault() {
            URIish url = null;
            SCM scm = project.getScm();
            if (!(scm instanceof GitSCM)) {
                throw new IllegalArgumentException("This repo does not use git.");
            }
            if (scm instanceof GitSCM) {
                List<RemoteConfig> repositories = ((GitSCM) scm).getRepositories();
                if (!repositories.isEmpty()) {
                    RemoteConfig defaultRepository = repositories.get(repositories.size() - 1);
                    List<URIish> uris = defaultRepository.getURIs();
                    if (!uris.isEmpty()) {
                        url = uris.get(uris.size() - 1);
                    }
                }
            }
            return url;
        }

        /**
         * Get the Name of the first declared repository in the project configuration.
         * Use this as default source repository Name.
         *
         * @return String with the default name of the source repository
         */
        protected String getSourceRepoNameDefault() {
            String result = null;
            SCM scm = project.getScm();
            if (!(scm instanceof GitSCM)) {
                throw new IllegalArgumentException("This repo does not use git.");
            }
            if (scm instanceof GitSCM) {
                List<RemoteConfig> repositories = ((GitSCM) scm).getRepositories();
                if (!repositories.isEmpty()) {
                    result = repositories.get(repositories.size() - 1).getName();
                }
            }
            return result;
        }

        public FormValidation doCheckGitlabHostUrl(@QueryParameter String value) {
            if (value == null || value.isEmpty()) {
                return FormValidation.error("Gitlab host URL required.");
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckGitlabApiToken(@QueryParameter String value) {
            if (value == null || value.isEmpty()) {
                return FormValidation.error("API Token for Gitlab access required");
            }

            return FormValidation.ok();
        }

        public FormValidation doTestConnection(@QueryParameter("gitlabHostUrl") final String hostUrl,
                                               @QueryParameter("gitlabApiToken") final String token, @QueryParameter("ignoreCertificateErrors") final boolean ignoreCertificateErrors) throws IOException {
            try {
                GitLab.checkConnection(token, hostUrl, ignoreCertificateErrors);
                return FormValidation.ok("Success");
            } catch (IOException e) {
                return FormValidation.error("Client error : " + e.getMessage());
            }
        }

        public GitLab getGitlab() {
            if (gitlab == null) {
                gitlab = new GitLab();
            }
            return gitlab;
        }

        public String getGitlabApiToken() {
            return gitlabApiToken;
        }

        public String getGitlabHostUrl() {
            return gitlabHostUrl;
        }

        public boolean getIgnoreCertificateErrors() {
            return ignoreCertificateErrors;
        }

        public static DescriptorImpl get() {
            return Trigger.all().get(DescriptorImpl.class);
        }

    }
}
