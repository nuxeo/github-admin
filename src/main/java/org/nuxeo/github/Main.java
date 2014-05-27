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
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.egit.github.core.client.GitHubClient;

/**
 *
 */
public class Main {

    private static final Log log = LogFactory.getLog(Main.class);

    private static Options options = null;

    protected static final String OPTION_HELP = "help";

    private static final String OPTION_HELP_DESC = "Show detailed help.";

    private static final String OPTION_TOKEN = "token";

    private static final String OPTION_TOKEN_DESC = "Use a Personal Access Token (OAuth)";

    private static final String OPTION_EXHAUSTIVE = "exhaustive";

    private static final String OPTION_EXHAUSTIVE_DESC = "Parse commits for an exhaustive analysis";

    private static final String OPTION_INPUT = "input";

    private static final String OPTION_INPUT_DESC = "Input file (defaults to /tmp/contributors.csv). Can equal to output file.";

    private static final String OPTION_OUTPUT = "output";

    private static final String OPTION_OUTPUT_DESC = "Output file (defaults to /tmp/contributors.csv). Can equal to input file.";

    public static void main(String[] args) throws IOException {
        Analyzer analyzer = parseArgs(args);
        if (analyzer == null) {
            return;
        }
        if (analyzer.analyzeAndPrint()) {
            System.exit(1);
        }
    }

    protected static Analyzer parseArgs(String[] args) {
        initParserOptions();
        CommandLineParser parser = new PosixParser();
        CommandLine cmdLine = null;
        Analyzer analyzer = null;
        try {
            cmdLine = parser.parse(options, args);
            List<String> cmdArgs = cmdLine.getArgList();
            log.info("Program arguments: " + Arrays.toString(args));
            if (cmdLine.hasOption(OPTION_HELP) || cmdArgs.contains(OPTION_HELP)) {
                printHelp();
                return null;
            }
            GitHubClient client = new GitHubClient();
            if (cmdLine.hasOption(OPTION_TOKEN)) {
                client.setOAuth2Token(cmdLine.getOptionValue(OPTION_TOKEN));
            }
            analyzer = new Analyzer(client);
            if (cmdLine.hasOption(OPTION_EXHAUSTIVE)) {
                if (!cmdLine.hasOption(OPTION_TOKEN)) {
                    throw new ParseException(
                            "A token is required for exhaustive analysis");
                }
                analyzer.setExhaustive(true);
            }
            if (cmdLine.hasOption(OPTION_INPUT)) {
                analyzer.setInput(cmdLine.getOptionValue(OPTION_INPUT));
            }
            if (cmdLine.hasOption(OPTION_OUTPUT)) {
                analyzer.setOutput(cmdLine.getOptionValue(OPTION_OUTPUT));
            }
            if (cmdLine.getArgList().isEmpty()
                    || cmdLine.getArgList().size() == 1
                    && "all".equals(cmdLine.getArgList().get(0))) {
                analyzer.setAllNuxeoRepositories();
            } else {
                for (String argList : (List<String>) cmdLine.getArgList()) {
                    for (String repo : argList.split(" ")) {
                        if (!repo.contains("/")) {
                            analyzer.setNuxeoRepository(repo);
                        } else {
                            String[] split = repo.split("/");
                            analyzer.setRepository(split[0], split[1]);
                        }
                    }
                }
            }
        } catch (ParseException | IOException e) {
            log.error(e.getMessage(), e);
            printHelp();
            return null;
        }
        return analyzer;
    }

    protected static void initParserOptions() {
        if (options != null) {
            return;
        }
        options = new Options();
        // help option
        OptionBuilder.withLongOpt(OPTION_HELP);
        OptionBuilder.withDescription(OPTION_HELP_DESC);
        options.addOption(OptionBuilder.create("h"));
        // token option
        OptionBuilder.withLongOpt(OPTION_TOKEN);
        OptionBuilder.withDescription(OPTION_TOKEN_DESC);
        OptionBuilder.isRequired(false);
        OptionBuilder.hasArg();
        options.addOption(OptionBuilder.create("t"));
        // exhaustive option
        OptionBuilder.withLongOpt(OPTION_EXHAUSTIVE);
        OptionBuilder.withDescription(OPTION_EXHAUSTIVE_DESC);
        options.addOption(OptionBuilder.create("e"));
        // input option
        OptionBuilder.withLongOpt(OPTION_INPUT);
        OptionBuilder.withDescription(OPTION_INPUT_DESC);
        OptionBuilder.hasArg();
        options.addOption(OptionBuilder.create("i"));
        // output option
        OptionBuilder.withLongOpt(OPTION_OUTPUT);
        OptionBuilder.withDescription(OPTION_OUTPUT_DESC);
        OptionBuilder.hasArg();
        options.addOption(OptionBuilder.create("o"));
    }

    public static void printHelp() {
        HelpFormatter help = new HelpFormatter();
        help.setSyntaxPrefix("Usage: ");
        help.printHelp(
                "java -jar github-admin.jar [options] [command] [repositories...]",
                options);
        System.out.println("Commands list:");
        System.out.println("\thelp\t\t\tPrint this message.");
        System.out.println("\trepositories\tList of repositories to analyze. "
                + "In the form: 'somerepo anotherrepo user/userrepo'. "
                + "If empty or equal to 'all', then all public non-fork Nuxeo repositories are analyzed.");
    }

}
