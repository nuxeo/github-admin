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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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

/**
 *
 */
public class Main {

    private static final List<String> EXCLUDES = Collections.unmodifiableList(Arrays.asList("jboss-seam"));

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.out.println("OAuth access token required");
            System.exit(1);
            return;
        }

        GitHubClient client = new GitHubClient();
        client.setOAuth2Token(args[0]);

        List<Contributor> allContributors = new ArrayList<Contributor>();
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
                    committer.setLogin(commitUser.getName() + " (anonymous)");
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
                    committer.setLogin(commitUser.getName() + " (anonymous)");
                    committer.setEmail(commitUser.getEmail());
                }
                if (committer.getLogin() == null) {
                    System.err.println("Found null! " + committer.getName());
                } else if (!allCommitters.containsKey(committer.getLogin())) {
                    allCommitters.put(committer.getLogin(), committer);
                }
            }

            // Using contributors list from RepositoryService
            List<Contributor> contributors = repoService.getContributors(repo,
                    true);
            allContributors.addAll(contributors);
        }

        System.out.println(String.format("Found %s committers",
                allCommitters.size()));
        for (User user : allCommitters.values()) {
            System.out.println(String.format("login: %s\t\t%s",
                    user.getLogin(), user.getUrl()));
        }

        System.out.println(String.format("Found %s contributors",
                allContributors.size()));
        Collections.sort(allContributors, new Comparator<Contributor>() {
            @Override
            public int compare(Contributor o1, Contributor o2) {
                String name1 = Contributor.TYPE_ANONYMOUS.equals(o1.getType()) ? o1.getName()
                        : o1.getLogin();
                String name2 = Contributor.TYPE_ANONYMOUS.equals(o2.getType()) ? o2.getName()
                        : o2.getLogin();
                return name1.compareToIgnoreCase(name2);
            }
        });
        for (Contributor contributor : allContributors) {
            if (Contributor.TYPE_ANONYMOUS.equals(contributor.getType())) {
                System.out.println(String.format("name:  %s (anonymous)",
                        contributor.getName()));
            } else {
                System.out.println(String.format("login: %s\t\t%s",
                        contributor.getLogin(), contributor.getUrl()));
            }
        }
    }
}
