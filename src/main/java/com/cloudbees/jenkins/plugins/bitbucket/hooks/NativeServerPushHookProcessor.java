package com.cloudbees.jenkins.plugins.bitbucket.hooks;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMNavigator;
import com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMSource;
import com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMSourceContext;
import com.cloudbees.jenkins.plugins.bitbucket.BranchSCMHead;
import com.cloudbees.jenkins.plugins.bitbucket.JsonParser;
import com.cloudbees.jenkins.plugins.bitbucket.PullRequestSCMHead;
import com.cloudbees.jenkins.plugins.bitbucket.PullRequestSCMRevision;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketRepository;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketRepositoryType;
import com.cloudbees.jenkins.plugins.bitbucket.endpoints.BitbucketCloudEndpoint;
import com.cloudbees.jenkins.plugins.bitbucket.server.client.BitbucketServerAPIClient;
import com.cloudbees.jenkins.plugins.bitbucket.server.client.pullrequest.BitbucketServerPullRequest;
import com.cloudbees.jenkins.plugins.bitbucket.server.client.repository.BitbucketServerRepository;
import com.cloudbees.jenkins.plugins.bitbucket.server.events.NativeServerRefsChangedEvent;
import com.google.common.base.Ascii;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.scm.SCM;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMEvent;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMHeadOrigin;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy;

public class NativeServerPushHookProcessor extends HookProcessor {

    private static final Logger LOGGER = Logger.getLogger(NativeServerPushHookProcessor.class.getName());

    @Override
    public void process(HookEventType hookEvent, String payload, BitbucketType instanceType, String origin) {
        return; // without a server URL, the event wouldn't match anything
    }

    @Override
    public void process(HookEventType hookEvent, String payload, BitbucketType instanceType, String origin,
            String serverUrl) {
        if (payload == null) {
            return;
        }

        final NativeServerRefsChangedEvent refsChangedEvent;
        try {
            refsChangedEvent = JsonParser.toJava(payload, NativeServerRefsChangedEvent.class);
        } catch (final IOException e) {
            LOGGER.log(Level.SEVERE, "Can not read hook payload", e);
            return;
        }

        final String owner = refsChangedEvent.getRepository().getOwnerName();
        final String repository = refsChangedEvent.getRepository().getRepositoryName();
        if (refsChangedEvent.getChanges().isEmpty()) {
            LOGGER.log(Level.INFO, "Received hook from Bitbucket. Processing push event on {0}/{1}",
                    new Object[] { owner, repository });
            scmSourceReIndex(owner, repository);
            return;
        }

        final Multimap<SCMEvent.Type, NativeServerRefsChangedEvent.Change> events = HashMultimap.create();
        for (final NativeServerRefsChangedEvent.Change change : refsChangedEvent.getChanges()) {
            final String type = change.getType();
            if ("UPDATE".equals(type)) {
                events.put(SCMEvent.Type.UPDATED, change);
            } else if ("DELETE".equals(type)) {
                events.put(SCMEvent.Type.REMOVED, change);
            } else if ("ADD".equals(type)) {
                events.put(SCMEvent.Type.CREATED, change);
            } else {
                LOGGER.log(Level.INFO, "Unknown change event type of {} received from Bitbucket Server", type);
            }
        }

        for (final SCMEvent.Type type : events.keySet()) {
            SCMHeadEvent.fireNow(new HeadEvent(type, events.get(type), origin, serverUrl, refsChangedEvent));
        }
    }

    private static final class HeadEvent extends SCMHeadEvent<Collection<NativeServerRefsChangedEvent.Change>> {
        private final String serverUrl;
        private final NativeServerRefsChangedEvent refsChangedEvent;
        private final Map<String, List<BitbucketServerPullRequest>> cachedPullRequests = new HashMap<>(4);

        HeadEvent(Type type, Collection<NativeServerRefsChangedEvent.Change> payload, String origin,
                String serverUrl, NativeServerRefsChangedEvent refsChangedEvent) {
            super(type, payload, origin);
            this.serverUrl = serverUrl;
            this.refsChangedEvent = refsChangedEvent;
        }

        @Override
        public boolean isMatch(@NonNull SCMNavigator navigator) {
            if (!(navigator instanceof BitbucketSCMNavigator)) {
                return false;
            }

            final BitbucketSCMNavigator bbNav = (BitbucketSCMNavigator) navigator;

            return isServerUrlMatch(bbNav.getServerUrl())
                    && bbNav.getRepoOwner().equalsIgnoreCase(refsChangedEvent.getRepository().getOwnerName());
        }

        private boolean isServerUrlMatch(String serverUrl) {
            if (serverUrl == null || BitbucketCloudEndpoint.SERVER_URL.equals(serverUrl)) {
                return false; // this is Bitbucket Cloud, which is not handled by this processor
            }

            return serverUrl.equals(this.serverUrl);
        }

        @NonNull
        @Override
        public String getSourceName() {
            return refsChangedEvent.getRepository().getRepositoryName();
        }

        @NonNull
        @Override
        public Map<SCMHead, SCMRevision> heads(@NonNull SCMSource source) {
            final BitbucketSCMSource src = getMatchingBitbucketSource(source);
            if (src == null) {
                return Collections.emptyMap();
            }

            final Map<SCMHead, SCMRevision> result = new HashMap<>();
            addBranches(src, result);
            addPullRequests(src, result);
            return result;
        }

        @Override
        public boolean isMatch(@NonNull SCM scm) {
            // TODO
            return false;
        }

        private BitbucketSCMSource getMatchingBitbucketSource(SCMSource source) {
            if (!(source instanceof BitbucketSCMSource)) {
                return null;
            }

            final BitbucketSCMSource src = (BitbucketSCMSource) source;
            if (!isServerUrlMatch(src.getServerUrl())) {
                return null;
            }

            final BitbucketRepositoryType type = BitbucketRepositoryType
                .fromString(refsChangedEvent.getRepository().getScm());
            if (type != BitbucketRepositoryType.GIT) {
                LOGGER.log(Level.INFO, "Received event for unknown repository type: {0}",
                        refsChangedEvent.getRepository().getScm());
                return null;
            }

            return src;
        }

        private boolean eventMatchesRepo(BitbucketServerRepository repo) {
            final Long eventRepoId = refsChangedEvent.getRepository().getId();
            return eventRepoId != null && eventRepoId.equals(repo.getId())
                    && eventMatchesRepo(repo.getOwnerName(), repo.getRepositoryName());
        }

        private boolean eventMatchesRepo(String ownerName, String repoName) {
            final BitbucketRepository repo = refsChangedEvent.getRepository();
            return repo.getOwnerName().equalsIgnoreCase(ownerName)
                    && repo.getRepositoryName().equalsIgnoreCase(repoName);
        }

        private void addBranches(BitbucketSCMSource src, Map<SCMHead, SCMRevision> result) {
            if (!eventMatchesRepo(src.getRepoOwner(), src.getRepository())) {
                return;
            }

            for (final NativeServerRefsChangedEvent.Change change : getPayload()) {
                if (!"BRANCH".equals(change.getRef().getType())) {
                    LOGGER.log(Level.INFO, "Received event for unknown ref type {0} of ref {1}",
                            new Object[] { change.getRef().getType(), change.getRef().getDisplayId() });
                    continue;
                }

                final BranchSCMHead head = new BranchSCMHead(change.getRef().getDisplayId(),
                        BitbucketRepositoryType.GIT);
                final SCMRevision revision = getType() == SCMEvent.Type.REMOVED ? null
                        : new AbstractGitSCMSource.SCMRevisionImpl(head, change.getToHash());
                result.put(head, revision);
            }
        }

        private void addPullRequests(BitbucketSCMSource src, Map<SCMHead, SCMRevision> result) {
            if (getType() != SCMEvent.Type.UPDATED) {
                return; // adds/deletes won't be handled here
            }

            final BitbucketSCMSourceContext ctx = new BitbucketSCMSourceContext(null, SCMHeadObserver.none())
                .withTraits(src.getTraits());
            if (!ctx.wantPRs()) {
                // doesn't want PRs, let the push event handle origin branches
                return;
            }

            final String sourceOwnerName = src.getRepoOwner();
            final String sourceRepoName = src.getRepository();
            final BitbucketServerRepository eventRepo = refsChangedEvent.getRepository();
            final SCMHeadOrigin headOrigin = src.originOf(eventRepo.getOwnerName(), eventRepo.getRepositoryName());
            final Set<ChangeRequestCheckoutStrategy> strategies = headOrigin == SCMHeadOrigin.DEFAULT
                    ? ctx.originPRStrategies() : ctx.forkPRStrategies();

            final List<BitbucketServerPullRequest> outgoingPullRequests = getOutgoingPullRequests(src);

            for (final NativeServerRefsChangedEvent.Change change : getPayload()) {
                if (!"BRANCH".equals(change.getRef().getType())) {
                    LOGGER.log(Level.INFO, "Received event for unknown ref type {0} of ref {1}",
                            new Object[] { change.getRef().getType(), change.getRef().getDisplayId() });
                    continue;
                }

                // iterate over all PRs that are originating from this change
                for (final BitbucketServerPullRequest pullRequest : outgoingPullRequests) {
                    final BitbucketServerRepository targetRepo = pullRequest.getDestination().getRepository();
                    // check if the target of the PR is actually this source
                    if (!sourceOwnerName.equalsIgnoreCase(targetRepo.getOwnerName())
                            || !sourceRepoName.equalsIgnoreCase(targetRepo.getRepositoryName())) {
                        continue;
                    }

                    for (final ChangeRequestCheckoutStrategy strategy : strategies) {
                        final String branchName = String.format("PR-%s%s", pullRequest.getId(),
                                strategies.size() > 1 ? "-" + Ascii.toLowerCase(strategy.name()) : "");

                        final PullRequestSCMHead head = new PullRequestSCMHead(branchName, sourceOwnerName,
                                sourceRepoName, BitbucketRepositoryType.GIT, branchName, pullRequest, headOrigin,
                                strategy);

                        final String targetHash = pullRequest.getDestination().getCommit().getHash();
                        final String pullHash = pullRequest.getSource().getCommit().getHash();

                        result.put(head,
                                new PullRequestSCMRevision<>(head,
                                        new AbstractGitSCMSource.SCMRevisionImpl(head.getTarget(), targetHash),
                                        new AbstractGitSCMSource.SCMRevisionImpl(head, pullHash)));
                    }
                }
            }
        }

        private List<BitbucketServerPullRequest> getOutgoingPullRequests(BitbucketSCMSource src) {
            final String serverUrl = src.getServerUrl();

            List<BitbucketServerPullRequest> pullRequests = cachedPullRequests.get(serverUrl);
            if (pullRequests != null) {
                return pullRequests;
            }

            final BitbucketServerRepository eventRepo = refsChangedEvent.getRepository();

            final BitbucketServerAPIClient api = (BitbucketServerAPIClient) src
                .buildBitbucketClient(eventRepo.getOwnerName(), eventRepo.getRepositoryName());

            pullRequests = new ArrayList<>();
            for (final NativeServerRefsChangedEvent.Change change : getPayload()) {
                if (!"BRANCH".equals(change.getRef().getType())) {
                    continue;
                }

                final List<BitbucketServerPullRequest> pullRequestsForChange;
                try {
                    pullRequestsForChange = api.getOutgoingOpenPullRequests(change.getRefId());
                } catch (final FileNotFoundException e) {
                    LOGGER.log(Level.INFO, "No such Repository on Bitbucket: {}", e.getMessage());
                    continue;
                } catch (IOException | InterruptedException | RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Failed to retrieve Pull Requests from Bitbucket", e);
                    continue;
                }

                for (final BitbucketServerPullRequest pullRequest : pullRequestsForChange) {
                    if (eventMatchesRepo(pullRequest.getSource().getRepository())) {
                        pullRequests.add(pullRequest);
                    }
                }
            }

            cachedPullRequests.put(serverUrl, pullRequests);
            return pullRequests;
        }
    }
}
