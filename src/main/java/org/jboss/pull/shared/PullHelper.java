/*
 * JBoss, Home of Professional Open Source.
 * Copyright (c) 2013, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.pull.shared;

import org.eclipse.egit.github.core.Comment;
import org.eclipse.egit.github.core.Label;
import org.eclipse.egit.github.core.Milestone;
import org.eclipse.egit.github.core.PullRequest;
import org.eclipse.egit.github.core.RepositoryBranch;
import org.jboss.logging.Logger;
import org.jboss.pull.shared.connectors.IssueHelper;
import org.jboss.pull.shared.connectors.bugzilla.BZHelper;
import org.jboss.pull.shared.connectors.RedhatPullRequest;
import org.jboss.pull.shared.connectors.github.GithubHelper;
import org.jboss.pull.shared.connectors.jira.JiraHelper;
import org.jboss.pull.shared.evaluators.PullEvaluatorFacade;
import org.jboss.pull.shared.spi.PullEvaluator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * A shared functionality regarding mergeable PRs, Github and Bugzilla.
 *
 * @author <a href="mailto:istudens@redhat.com">Ivo Studensky</a>
 * @author wangchao
 */
public class PullHelper {
    private Logger LOGGER = Logger.getLogger(PullHelper.class);

    // private final Properties props;
    private final PullEvaluatorFacade evaluatorFacade;

    private final UserList adminList;

    // ------- Specific Helpers
    private GithubHelper ghHelper;
    private IssueHelper bzHelper;
    private IssueHelper jiraHelper;

    private final Properties props;

    public Properties getProperties() {
        return props;
    }

    public PullHelper(final String configurationFileProperty, final String configurationFileDefault) {
        try {
            ghHelper = new GithubHelper(configurationFileProperty, configurationFileDefault);
            bzHelper = new BZHelper(configurationFileProperty, configurationFileDefault);
            jiraHelper = new JiraHelper(configurationFileProperty, configurationFileDefault);

            props = Util.loadProperties(configurationFileProperty, configurationFileDefault);

            // initialize evaluators
            evaluatorFacade = new PullEvaluatorFacade(this, props);

            adminList = UserList.loadUserList(Util.require(props, "admin.list.file"));

        } catch (IOException e) {
            throw new RuntimeException("Can not load properties from configuration file", e);
        }
    }

    public List<String> getBranches() {
        List<RepositoryBranch> branches = ghHelper.getBranches();
        List<String> branchNames = new ArrayList<String>();
        for (RepositoryBranch branch : branches) {
            String branchName = branch.getName();
            if (!branchName.contains("ignore") && !branchName.contains("proposed")) {
                branchNames.add(branchName);
            }
        }
        return branchNames;
    }

    public PullEvaluatorFacade getEvaluatorFacade() {
        return evaluatorFacade;
    }

    public List<RedhatPullRequest> getOpenPullRequests() {
        List<PullRequest> pullRequests = ghHelper.getPullRequests("open");

        List<RedhatPullRequest> redhatPullRequests = new ArrayList<RedhatPullRequest>();

        for (PullRequest pullRequest : pullRequests) {
            LOGGER.info("Found PR #" + pullRequest.getNumber());
            redhatPullRequests.add(new RedhatPullRequest(pullRequest, bzHelper, jiraHelper, ghHelper));
        }

        return redhatPullRequests;
    }

    public RedhatPullRequest getPullRequest(String organization, String repository, int id) {
        PullRequest pullRequest = ghHelper.getPullRequest(organization, repository, id);
        return new RedhatPullRequest(pullRequest, bzHelper, jiraHelper, ghHelper);
    }

    public List<Milestone> getGithubMilestones() {
        return ghHelper.getMilestones();
    }

    public Milestone createMilestone(String title) {
        return ghHelper.createMilestone(title);
    }

    /**
     * Checks the state of the given pull request from the pull-processor perspective.
     *
     * @param pullRequest the pull request
     * @return relevant state
     */
    public ProcessorPullState checkPullRequestState(final RedhatPullRequest pullRequest) {
        ProcessorPullState result = ProcessorPullState.NEW;

        final List<Comment> comments = pullRequest.getGithubComments();
        for (Comment comment : comments) {
            if (ghHelper.getGithubLogin().equals(comment.getUser().getLogin())) {
                if (Constants.PENDING.matcher(comment.getBody()).matches()) {
                    result = ProcessorPullState.PENDING;
                    continue;
                }

                if (Constants.RUNNING.matcher(comment.getBody()).matches()) {
                    result = ProcessorPullState.RUNNING;
                    continue;
                }

                if (Constants.FINISHED.matcher(comment.getBody()).matches()) {
                    result = ProcessorPullState.FINISHED;
                    continue;
                }
            }

            if (Constants.MERGE.matcher(comment.getBody()).matches()) {
                result = ProcessorPullState.MERGEABLE;
                continue;
            }
        }

        if (result == ProcessorPullState.MERGEABLE || result == ProcessorPullState.NEW) {
            // check other conditions, i.e. upstream pull request and bugzilla and jira...
            final PullEvaluator.Result mergeable = evaluatorFacade.isMergeable(pullRequest);
            if (!mergeable.isMergeable()) {
                result = ProcessorPullState.INCOMPLETE;
            }

            if (result == ProcessorPullState.INCOMPLETE && !comments.isEmpty()) {
                Comment lastComment = comments.get(comments.size() - 1);
                if (Constants.FORCE_MERGE.matcher(lastComment.getBody()).matches()
                        && isAdminUser(lastComment.getUser().getLogin()))
                    result = ProcessorPullState.MERGEABLE;
            }
        }

        return result;
    }

    public boolean isAdminUser(final String username) {
        return adminList.has(username);
    }

    public Label getLabel(String title) {
        return ghHelper.getLabel(title);
    }

}
