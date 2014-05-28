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
import org.apache.commons.lang.ArrayUtils;
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

import au.com.bytecode.opencsv.CSVReader;
import au.com.bytecode.opencsv.CSVWriter;

import com.google.gson.JsonSyntaxException;

public class Analyzer {

    /**
     *
     */
    private static final String[] CSV_HEADER = new String[] { "Login", "Name",
            "Signed", "Emails", "Company", "URL", "Aliases", "Commits",
            "Trivial commits" };

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
            "jboss-seam", "jodconverter.bak", "richfaces", "daisydiff",
            "h2database"));

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

    private Path output;

    private Path input;

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
            if (repo.isPrivate() || repo.isFork()
                    || EXCLUDES.contains(repo.getName())) {
                log.debug("Skipped " + repo.getName());
                continue;
            }
            log.info("Add for analysis: " + repo.getHtmlUrl());
            repositories.add(repo);
        }
    }

    /**
     * @throws IOException
     */
    public void setNuxeoRepository(String repo) throws IOException {
        Repository repository = repoService.getRepository("nuxeo", repo);
        log.info("Add for analysis: " + repository.getHtmlUrl());
        repositories.add(repository);
    }

    /**
     * @throws IOException
     */
    public void setRepository(String owner, String repo) throws IOException {
        Repository repository = repoService.getRepository(owner, repo);
        log.info("Add for analysis: " + repository.getHtmlUrl());
        repositories.add(repository);
    }

    /**
     * @return true if there are unsigned contributors
     * @throws IOException
     */
    public boolean analyzeAndPrint() throws IOException {
        load();
        setNuxeoDevelopers();
        // printContributors();
        for (Repository repo : repositories) {
            getContributors(repo);
        }
        fillAndSyncDevMaps();
        if (exhaustive) {
            // printCommitters();
            for (Repository repo : repositories) {
                getCommitters(repo);
            }
            fillAndSyncDevMaps();
        }
        // Need to also parse closed pull-requests?
        return saveAndPrint();
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

    protected void load() {
        if (input == null) {
            input = Paths.get(System.getProperty("java.io.tmpdir"),
                    "contributors.csv");
        }
        if (!Files.isReadable(input)) {
            return;
        }
        try (CSVReader reader = new CSVReader(Files.newBufferedReader(input,
                Charset.defaultCharset()), '\t')) {
            // Check header
            String[] header = reader.readNext();
            if (!ArrayUtils.isEquals(CSV_HEADER, header)) {
                log.warn("Header mismatch " + Arrays.toString(header));
                return;
            }
            String[] nextLine;
            while ((nextLine = reader.readNext()) != null) {
                Developer dev = parse(nextLine);
                if (dev.isAnonymous()) {
                    developersByName.put(dev.getName(), dev);
                } else {
                    developersByLogin.put(dev.getLogin(), dev);
                }
            }
            for (Developer dev : developersByLogin.values()) {
                for (String alias : dev.getAliases()) {
                    developersByLogin.get(alias).updateWith(dev);
                }
            }
            for (Developer dev : developersByName.values()) {
                for (String alias : dev.getAliases()) {
                    developersByLogin.get(alias).updateWith(dev);
                }
            }
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }

    /**
     * @return true if there are unsigned contributors
     */
    protected boolean saveAndPrint() {
        Set<Developer> allContributors = new TreeSet<>();
        allContributors.addAll(developersByLogin.values());
        allContributors.addAll(developersByName.values());
        log.info(String.format("Found %s contributors", allContributors.size()));
        if (output == null) {
            output = Paths.get(System.getProperty("java.io.tmpdir"),
                    "contributors.csv");
        }
        boolean unsigned = false;
        try (CSVWriter writer = new CSVWriter(Files.newBufferedWriter(output,
                Charset.defaultCharset()), '\t')) {
            writer.writeNext(CSV_HEADER);
            for (Developer dev : allContributors) {
                if (!unsigned && dev.getAliases().isEmpty()
                        && !"Nuxeo".equalsIgnoreCase(dev.getCompany())
                        && !dev.isSigned()) {
                    unsigned = true;
                }
                log.debug(dev);
                writer.writeNext(new String[] {
                        dev.getLogin(),
                        dev.getName(),
                        Boolean.toString(dev.isSigned()),
                        setToString(dev.getEmails()),
                        dev.getCompany(),
                        dev.getUrl(),
                        setToString(dev.getAliases()),
                        "Nuxeo".equalsIgnoreCase(dev.getCompany())
                                || "ex-Nuxeo".equalsIgnoreCase(dev.getCompany()) ? ""
                                : commitsToString(dev.getCommits()) });
            }
            log.info("Saved to file: " + output);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
        return unsigned;
    }

    private String setToString(Set<String> strings) {
        StringBuilder sb = new StringBuilder();
        for (Iterator<String> it = strings.iterator(); it.hasNext();) {
            sb.append(it.next());
            if (it.hasNext()) {
                sb.append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    private String commitsToString(Set<String> strings) {
        StringBuilder sb = new StringBuilder();
        String base = "none";
        for (Iterator<String> it = strings.iterator(); it.hasNext();) {
            String next = it.next();
            if (next.startsWith(base) && base.endsWith("commit")) {
                sb.append(next.substring(base.length() + 1));
            } else {
                sb.append(next);
                base = next.substring(0, next.lastIndexOf('/'));
            }
            if (it.hasNext()) {
                sb.append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    protected void fillAndSyncDevMaps() throws IOException {
        // Fill missing values for known developers (with login)
        for (Developer dev : developersByLogin.values()) {
            if (!dev.isComplete()) {
                User nxDev = nxDevelopersByLogin.get(dev.getLogin());
                if (nxDev != null) {
                    dev.set(nxDev);
                    dev.setCompany("Nuxeo");
                }
            }
            if (!dev.isComplete()) {
                try {
                    // TODO: use a cache
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
                if (removed != null) {
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

        // Merge developersByName into developersByLogin when an email matches
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
                    devWithLogin.updateWith(dev);
                    it.remove();
                    break;
                }
            }
        }

        // Update allDevelopersByName
        allDevelopersByName.putAll(developersByName);
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
                log.error("Failure with: " + repository.getUrl(), e);
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
            if (contributor.getLogin() == null) {
                Developer dev = developersByName.get(contributor.getName());
                if (dev == null) {
                    dev = new Developer(contributor);
                } else {
                    // continue;
                    dev.updateWith(new Developer(contributor));
                }
                dev.addRepository(repo);
                developersByName.put(contributor.getName(), dev);
            } else {
                Developer dev = developersByLogin.get(contributor.getLogin());
                if (dev == null) {
                    dev = new Developer(contributor);
                } else {
                    // continue;
                    dev.updateWith(new Developer(contributor));
                }
                if (!nxDevelopersByLogin.containsKey(dev.getLogin())) {
                    dev.addRepository(repo);
                }
                developersByLogin.put(contributor.getLogin(),
                        dev.updateWith(new Developer(contributor)));
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
            getCommitters(repo);
        }
        fillAndSyncDevMaps();
        saveAndPrint();
    }

    /**
     * Extracting contributors list from CommitService
     */
    protected void getCommitters(Repository repo) throws IOException {
        log.debug("Parsing " + repo.getName());
        List<RepositoryCommit> commits = getRepositoryCommits(repo);
        for (RepositoryCommit commit : commits) {
            getCommitter(commit, commit.getAuthor(),
                    commit.getCommit().getAuthor());
            getCommitter(commit, commit.getCommitter(),
                    commit.getCommit().getCommitter());
        }
    }

    protected void getCommitter(RepositoryCommit commit, User committer,
            CommitUser commitUser) {
        if (committer == null || committer.getLogin() == null) {
            Developer dev = allDevelopersByName.get(commitUser.getName());
            if (dev == null) {
                dev = new Developer(commitUser.getName());
                dev.addEmail(commitUser.getEmail());
                developersByName.put(dev.getName(), dev);
                allDevelopersByName.put(dev.getName(), dev);
            }
            dev.addCommit(commit);
        } else {
            Developer dev = developersByLogin.get(committer.getLogin());
            if (dev == null) {
                dev = new Developer(committer);
                developersByLogin.put(committer.getLogin(), dev);
                if (StringUtils.isNotBlank(dev.getName())) {
                    allDevelopersByName.put(dev.getName(), dev);
                }
            }
            if (!nxDevelopersByLogin.containsKey(dev.getLogin())) {
                dev.addCommit(commit);
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
    public void setOutput(String output) {
        this.output = Paths.get(output);
    }

    public void setInput(String input) {
        this.input = Paths.get(input);
    }

    /**
     * @param line String[] { "Login", "Name", "Emails",
     *            "Company", "URL", "Aliases", "Signed" }
     */
    public Developer parse(String[] line) {
        Developer dev = new Developer();
        String str = line[0];
        if (StringUtils.isNotBlank(str)) {
            dev.setLogin(str);
            dev.setAnonymous(false);
        } else {
            dev.setAnonymous(true);
        }
        str = line[1];
        if (StringUtils.isNotBlank(str)) {
            dev.setName(str);
        }
        str = line[2];
        if (StringUtils.isNotBlank(str)) {
            dev.signed = Boolean.parseBoolean(str);
        }
        str = line[3];
        if (StringUtils.isNotBlank(str)) {
            String[] emails = str.trim().split(System.lineSeparator());
            for (String email : emails) {
                dev.addEmail(email.trim());
            }
        }
        str = line[4];
        if (StringUtils.isNotBlank(str)) {
            dev.setCompany(str);
        }
        str = line[5];
        if (StringUtils.isNotBlank(str)) {
            dev.setUrl(str);
        }
        str = line[6];
        if (StringUtils.isNotBlank(str)) {
            String[] aliases = str.trim().split(System.lineSeparator());
            for (String alias : aliases) {
                dev.aliases.add(alias.trim());
            }
        }
        str = line[7];
        if (StringUtils.isNotBlank(str)) {
            String[] commits = str.trim().split(System.lineSeparator());
            for (String commit : commits) {
                dev.commits.add(commit.trim());
            }
        }
        return dev;
    }
}
