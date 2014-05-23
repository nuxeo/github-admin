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
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.Predicate;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.egit.github.core.CommitUser;
import org.eclipse.egit.github.core.Contributor;
import org.eclipse.egit.github.core.Repository;
import org.eclipse.egit.github.core.RepositoryCommit;
import org.eclipse.egit.github.core.User;
import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.client.RequestException;
import org.eclipse.egit.github.core.service.CommitService;
import org.eclipse.egit.github.core.service.RepositoryService;
import org.eclipse.egit.github.core.service.TeamService;
import org.eclipse.egit.github.core.service.UserService;

import au.com.bytecode.opencsv.CSVWriter;

import com.google.gson.JsonSyntaxException;

public class Analyzer {

    private final class NuxeoEmailPredicate implements Predicate<String> {
        @Override
        public boolean evaluate(String email) {
            return email != null && email.endsWith("@nuxeo.com");
        }
    }

    private static final Log log = LogFactory.getLog(Analyzer.class);

    /**
     * "Developers" team ID in Nuxeo organization
     */
    private static final int DEVELOPERS_TEAM_ID = 35421;

    private static final List<String> EXCLUDES = Collections.unmodifiableList(Arrays.asList(
            "jboss-seam", "jodconverter.bak", "richfaces"));

    private List<Repository> repositories = new ArrayList<>();

    private CommitService commitService;

    private RepositoryService repoService;

    private UserService userService;

    private TeamService teamService;

    private boolean exhaustive = false;

    private Map<String, Developer> developersByLogin = new TreeMap<>();

    private Map<String, Developer> developersByName = new TreeMap<>();

    private Map<String, User> nxDevelopersByLogin = new TreeMap<>();

    private Map<Long, List<RepositoryCommit>> commitsByRepository = new HashMap<>();

    private Map<String, Developer> allDevelopersByName = new TreeMap<>();

    private Path path;

    public Analyzer(GitHubClient client) {
        repoService = new RepositoryService(client);
        commitService = new CommitService(client);
        userService = new UserService(client);
        teamService = new TeamService(client);
    }

    /**
     * @param exhaustive if true, allow parsing commits per repository
     */
    public void setExhaustive(boolean exhaustive) {
        this.exhaustive = exhaustive;
    }

    /**
     * Get all Nuxeo repositories. Excludes:<br/>
     * <ul>
     * <li>private repositories,</li>
     * <li>forked repositories,</li>
     * <li>fixed list from {@link #EXCLUDES}</li>
     * </ul>
     *
     * @throws IOException
     */
    public void setAllNuxeoRepositories() throws IOException {
        for (Repository repo : repoService.getRepositories("nuxeo")) {
            if (repo.isPrivate() || repo.isFork() || EXCLUDES.contains(repo)) {
                log.debug("Skipped " + repo.getName());
                continue;
            }
            repositories.add(repo);
        }
    }

    /**
     * @throws IOException
     */
    public void setNuxeoRepository(String repo) throws IOException {
        repositories.add(repoService.getRepository("nuxeo", repo));
    }

    /**
     * @throws IOException
     */
    public void setRepository(String owner, String repo) throws IOException {
        repositories.add(repoService.getRepository(owner, repo));
    }

    /**
     * @throws IOException
     */
    public void analyzeAndPrint() throws IOException {
        setNuxeoDevelopers();

        // printContributors();
        for (Repository repo : repositories) {
            getContributors(repo);
        }
        fillAndSyncDevMaps();

        if (exhaustive) {
            // printCommitters();
            for (Repository repo : repositories) {
                getCommittors(repo);
            }
            fillAndSyncDevMaps();
        }

        // Need to also parse closed pull-request?

        saveAndPrint();
    }

    /**
     * Quick method based on GitHub service
     *
     * @param nxDevelopers
     * @param nxContributorsByLogin
     * @throws IOException
     */
    protected void printContributors() throws IOException {
        for (Repository repo : repositories) {
            getContributors(repo);
        }
        fillAndSyncDevMaps();
        saveAndPrint();
    }

    protected void saveAndPrint() {
        Set<Developer> allContributors = new TreeSet<>();
        allContributors.addAll(developersByLogin.values());
        allContributors.addAll(developersByName.values());
        log.info(String.format("Found %s contributors", allContributors.size()));
        if (path == null) {
            path = Paths.get(System.getProperty("java.io.tmpdir"),
                    "contributors.csv");
        }
        try (CSVWriter writer = new CSVWriter(Files.newBufferedWriter(path,
                Charset.defaultCharset()), '\t')) {
            writer.writeNext(new String[] { "Login", "Name", "Emails",
                    "Company", "URL", "Comments" });
            for (Developer dev : allContributors) {
                if (dev.getLogin() != null
                        && nxDevelopersByLogin.containsKey(dev.getLogin())) {
                    log.debug("Ignored " + dev);
                    // continue;
                }
                log.info(dev);
                writer.writeNext(new String[] { dev.getLogin(), dev.getName(),
                        dev.getEmails().toString(), dev.getCompany(),
                        dev.getUrl() });
            }
            log.info("Saved to file: " + path);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }

    protected void fillAndSyncDevMaps() throws IOException {
        // Fill missing values for known developers (with login)
        for (Developer dev : developersByLogin.values()) {
            if (dev.isComplete()) {
                continue;
            }
            User nxDev = nxDevelopersByLogin.get(dev.getLogin());
            if (nxDev != null) {
                dev.set(nxDev);
                dev.setCompany("Nuxeo");
            }
            if (!dev.isComplete()) {
                try {
                    dev.set(userService.getUser(dev.getLogin()));
                } catch (IOException e) {
                    if (e.getCause() instanceof JsonSyntaxException) {
                        // ignore
                    } else {
                        throw e;
                    }
                }
            }
            if (dev.getName() != null) {
                Developer removed = developersByName.remove(dev.getName());
                if (!dev.isComplete() && removed != null) {
                    dev.updateWith(removed);
                }
            }
            if (findEmail(dev) && dev.getCompany() == null) {
                if (CollectionUtils.exists(dev.getEmails(),
                        new NuxeoEmailPredicate())) {
                    dev.setCompany("Nuxeo (ex?)");
                }
            }
        }

        // Look for unknown developers' email in commits
        for (Developer dev : developersByName.values()) {
            if (findEmail(dev) && dev.getCompany() == null) {
                if (CollectionUtils.exists(dev.getEmails(),
                        new NuxeoEmailPredicate())) {
                    dev.setCompany("Nuxeo (ex?)");
                }
            }
        }

        // Merge developersByName into developersByLogin when match
        for (Iterator<Entry<String, Developer>> it = developersByName.entrySet().iterator(); it.hasNext();) {
            Developer dev = it.next().getValue();
            if (dev.getEmails().isEmpty()) {
                log.warn("Couldn't find email for " + dev);
                continue;
            }
            for (Developer devWithLogin : developersByLogin.values()) {
                if (!CollectionUtils.intersection(devWithLogin.getEmails(),
                        dev.getEmails()).isEmpty()
                        || devWithLogin.getLogin().equals(dev.getName())) {
                    // if (!devWithLogin.getEmails().isEmpty()
                    // && !dev.getEmails().isEmpty()
                    // || !Collections.disjoint(devWithLogin.getEmails(),
                    // dev.getEmails())) {
                    devWithLogin.updateWith(dev);
                    it.remove();
                    break;
                }
            }
        }

        // Update allDevelopersByName
        for (Developer dev : developersByLogin.values()) {
            if (StringUtils.isNotEmpty(dev.getName())) {
                allDevelopersByName.put(dev.getName(), dev);
            }
        }
    }

    // TODO: time consuming; improve the impl using the commits cache
    protected boolean findEmail(Developer dev) throws IOException {
        if (!dev.getEmails().isEmpty()) {
            return true;
        }
        log.debug("Looking for commits from " + dev);
        for (Repository repository : repositories) {
            List<RepositoryCommit> commits = getRepositoryCommits(repository);
            for (RepositoryCommit commit : commits) {
                CommitUser committer = commit.getCommit().getAuthor();
                if (committer != null
                        && (committer.getName().equals(dev.getName()) || committer.getName().equals(
                                dev.getLogin()))) {
                    dev.addEmail(committer.getEmail());
                    return true;
                }
                committer = commit.getCommit().getCommitter();
                if (committer != null
                        && (committer.getName().equals(dev.getName()) || committer.getName().equals(
                                dev.getLogin()))) {
                    dev.addEmail(committer.getEmail());
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Keeps a cache ({@link #commitsByRepository}) to avoid repetitive requests
     */
    private List<RepositoryCommit> getRepositoryCommits(Repository repository)
            throws IOException {
        List<RepositoryCommit> commits = commitsByRepository.get(repository.getId());
        if (commits == null) {
            try {
                log.debug("Get commits from " + repository);
                commits = commitService.getCommits(repository);
            } catch (RequestException e) {
                log.error(e.getMessage(), e);
                commits = new ArrayList<>();
            }
            commitsByRepository.put(repository.getId(), commits);
        }
        return commits;
    }

    protected void getContributors(Repository repo) throws IOException {
        // Using contributors list from RepositoryService, include anonymous
        List<Contributor> contributors = repoService.getContributors(repo, true);
        for (Contributor contributor : contributors) {
            if (contributor.getLogin() == null
                    && !developersByName.containsKey(contributor.getName())) {
                developersByName.put(contributor.getName(), new Developer(
                        contributor));
            } else if (contributor.getLogin() != null
                    && !developersByLogin.containsKey(contributor.getLogin())) {
                developersByLogin.put(contributor.getLogin(), new Developer(
                        contributor));
            }
        }
    }

    /**
     * Heavy method analyzing all commits
     *
     * @param nxDevelopers
     * @param allContributors
     * @throws IOException
     *
     */
    protected void printCommitters() throws IOException {
        for (Repository repo : repositories) {
            getCommittors(repo);
        }
        fillAndSyncDevMaps();
        saveAndPrint();
    }

    protected void getCommittors(Repository repo) throws IOException {
        // Extracting contributors list from CommitService
        log.debug("Parsing " + repo.getName());

        List<RepositoryCommit> commits = getRepositoryCommits(repo);
        for (RepositoryCommit commit : commits) {
            User committer = commit.getAuthor();
            if (committer == null) {
                CommitUser commitUser = commit.getCommit().getAuthor();
                if (!allDevelopersByName.containsKey(commitUser.getName())) {
                    Developer dev = new Developer(commitUser.getName());
                    dev.addEmail(commitUser.getEmail());
                    developersByName.put(dev.getName(), dev);
                    allDevelopersByName.put(dev.getName(), dev);
                }
            } else if (committer.getLogin() != null
                    && !developersByLogin.containsKey(committer.getLogin())) {
                developersByLogin.put(committer.getLogin(), new Developer(
                        committer));
            }
            committer = commit.getCommitter();
            if (committer == null) {
                CommitUser commitUser = commit.getCommit().getAuthor();
                if (!allDevelopersByName.containsKey(commitUser.getName())) {
                    Developer dev = new Developer(commitUser.getName());
                    dev.addEmail(commitUser.getEmail());
                    developersByName.put(dev.getName(), dev);
                    allDevelopersByName.put(dev.getName(), dev);
                }
            } else if (committer.getLogin() != null
                    && !developersByLogin.containsKey(committer.getLogin())) {
                developersByLogin.put(committer.getLogin(), new Developer(
                        committer));
            }
        }
    }

    protected void setNuxeoDevelopers() throws IOException {
        if (!"Developers".equalsIgnoreCase(teamService.getTeam(
                DEVELOPERS_TEAM_ID).getName())) {
            throw new IOException("Wrong team ID");
        } else {
            List<User> users = teamService.getMembers(DEVELOPERS_TEAM_ID);
            for (User user : users) {
                nxDevelopersByLogin.put(user.getLogin(), user);
            }
        }
    }

    /**
     * @param optionValue Absolute or relative path to the output file.
     */
    public void setOutput(String path) {
        this.path = Paths.get(path);
    }
}
