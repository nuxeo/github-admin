/*
 * (C) Copyright 2014 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Julien Carsique
 *
 */

package org.nuxeo.github;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.egit.github.core.CommitUser;
import org.eclipse.egit.github.core.Contributor;
import org.eclipse.egit.github.core.Repository;
import org.eclipse.egit.github.core.RepositoryCommit;
import org.eclipse.egit.github.core.User;
import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.client.RequestException;
import org.eclipse.egit.github.core.service.CommitService;
import org.eclipse.egit.github.core.service.RepositoryService;
import org.eclipse.egit.github.core.service.UserService;

/**
 *
 */
public class Main {

    private static final List<String> EXCLUDES = Collections.unmodifiableList(Arrays.asList("jboss-seam"));

    private static boolean exhaustive = true;

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.out.println("OAuth access token required");
            System.exit(1);
            return;
        }

        GitHubClient client = new GitHubClient();
        client.setOAuth2Token(args[0]);

        Map<String, Contributor> allContributors = new TreeMap<String, Contributor>();
        Map<String, User> allCommitters = new TreeMap<String, User>();
        RepositoryService repoService = new RepositoryService(client);
        // Repository repo = repoService.getRepository("nuxeo",
        // "nuxeo-platform-login");
        for (Repository repo : repoService.getRepositories("nuxeo")) {
            if (repo.isPrivate() || repo.isFork() || EXCLUDES.contains(repo)) {
                System.out.println("Skipped " + repo.getName());
                continue;
            } else {
                System.out.println("Parsing " + repo.getName());
            }

            if (exhaustive) {
                // Extracting contributors list from CommitService
                CommitService commitService = new CommitService(client);
                List<RepositoryCommit> commits = null;
                try {
                    commits = commitService.getCommits(repo);
                } catch (RequestException e) {
                    System.err.println(e);
                    continue;
                }
                for (RepositoryCommit commit : commits) {
                    User committer = commit.getCommitter();
                    if (committer == null) {
                        CommitUser commitUser = commit.getCommit().getCommitter();
                        committer = new User();
                        committer.setName(commitUser.getName());
                        committer.setLogin(commitUser.getName()
                                + " (anonymous)");
                        committer.setEmail(commitUser.getEmail());
                    }
                    if (committer.getLogin() == null) {
                        System.err.println("Found null! " + committer.getName());
                    } else if (!allCommitters.containsKey(committer.getLogin())) {
                        allCommitters.put(committer.getLogin(), committer);
                    }

                    committer = commit.getAuthor();
                    if (committer == null) {
                        CommitUser commitUser = commit.getCommit().getAuthor();
                        committer = new User();
                        committer.setName(commitUser.getName());
                        committer.setLogin(commitUser.getName()
                                + " (anonymous)");
                        committer.setEmail(commitUser.getEmail());
                    }
                    if (committer.getLogin() == null) {
                        System.err.println("Found null! " + committer.getName());
                    } else if (!allCommitters.containsKey(committer.getLogin())) {
                        allCommitters.put(committer.getLogin(), committer);
                    }
                }
            }

            // Using contributors list from RepositoryService
            List<Contributor> contributors = repoService.getContributors(repo,
                    true);
            for (Contributor contributor : contributors) {
                if (Contributor.TYPE_ANONYMOUS.equals(contributor.getType())
                        || contributor.getLogin() == null) {
                    contributor.setLogin(contributor.getName() + " (anonymous)");
                }
                if (!allContributors.containsKey(contributor.getLogin())) {
                    allContributors.put(contributor.getLogin(), contributor);
                }
            }
        }

        System.out.println(String.format("Found %s committers",
                allCommitters.size()));
        for (User user : allCommitters.values()) {
            System.out.println(String.format("login: %s\t\t%s\t\t%s",
                    user.getLogin(), user.getEmail(), user.getUrl()));
        }

        UserService userService = new UserService(client);
        System.out.println(String.format("Found %s contributors",
                allContributors.size()));
        for (Contributor contributor : allContributors.values()) {
            String email = "";
            if (!Contributor.TYPE_ANONYMOUS.equals(contributor.getType())) {
                User user = userService.getUser(contributor.getLogin());
                email = user.getEmail();
            }
            System.out.println(String.format("login: %s\t\t%s\t\t%s",
                    contributor.getLogin(), email, contributor.getUrl()));
        }
    }
}
