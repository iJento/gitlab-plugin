package com.dabsquared.gitlabjenkins;

import hudson.triggers.SCMTrigger;

import java.io.File;
import java.io.IOException;

/**
 * Created by daniel on 6/8/14.
 */
public class GitLabPushCause extends SCMTrigger.SCMTriggerCause {

    private String cause;
   private String pushedBy;

    public GitLabPushCause(String cause) {
        this.cause = cause;
    }

    public GitLabPushCause(String cause, File logFile) throws IOException {
        super(logFile);
        this.cause = cause;
    }

    public GitLabPushCause(String cause, String pollingLog) {
        super(pollingLog);
        this.cause = cause;
    }

    @Override
    public String getShortDescription() {
       if(pushedBy != null && cause == null){
          cause = pushedBy;
       }
        if (cause == null) {
            return "Started by GitLab push";
        } else {
            return String.format("Started by GitLab push to %s", cause);
        }
    }
}
