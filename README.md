# About

This project provides administration tooling for GitHub.

Current features:

- Contributors listing.
- Search for non-Nuxeo contributors who've committed non trivial code but didn't sign the Contributor Agreement.
- Alias to associate an anonymous with a known developer. 

## Usage

Display usage with:

    $ mvn test -Phelp
    or
    $ java -jar target/github-admin-*-shaded.jar -h

    Usage: java -jar github-admin.jar [options] [command] [repositories...]
     -e,--exhaustive     Parse commits for an exhaustive analysis
     -h,--help           Show detailed help.
     -i,--input <arg>    Input file (defaults to /tmp/contributors.csv). Can equal to output file.
     -o,--output <arg>   Output file (defaults to /tmp/contributors.csv). Can equal to input file.
     -t,--token <arg>    Use a Personal Access Token (OAuth)
    Commands list:
      help      Print this message.
      repositories  List of repositories to analyze. In the form: 'somerepo anotherrepo user/userrepo'. If empty or equal to 'all', then all public non-fork Nuxeo repositories are analyzed.

### Run with Maven
   
    $ mvn test -Prun [-Dgithub.token=...] [-Drepo=...]
    
### Run with Java

    $ java -jar target/github-admin-0.0.1-SNAPSHOT.jar [options] [command] [repositories...]
    
### Edit contributors.csv for successive executions

You can edit the output file and provide it as input in order to:

- change the company,
- merge users: set another login as alias,
- change the signed value,
- ignore trivial commits.

