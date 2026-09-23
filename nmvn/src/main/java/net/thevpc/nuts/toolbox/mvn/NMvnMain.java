package net.thevpc.nuts.toolbox.mvn;

import net.thevpc.nuts.app.NApp;
import net.thevpc.nuts.app.NApplication;
import net.thevpc.nuts.app.NAppRun;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.command.NExecutionException;
import net.thevpc.nuts.core.NSession;
import net.thevpc.nuts.io.NOut;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.toolbox.mvn.subcommands.MvnCleanSubCommand;
import net.thevpc.nuts.toolbox.mvn.subcommands.MvnJarCompareSubCommand;
import net.thevpc.nuts.toolbox.mvn.subcommands.MvnVersionSubCommand;
import net.thevpc.nuts.toolbox.mvn.subcommands.MvnWorksetSubCommand;
import net.thevpc.nuts.toolbox.mvn.util.MavenCliWrapper;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NRef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@NApp
public class NMvnMain {
    private static final Logger LOG = Logger.getLogger(NMvnMain.class.getName());
//    public static void main(String[] args) {
//        main0(new String[]{
//                "--json", "--get", "test:classpath:1.3", "vpc-public-maven"
//        });
//    }

    public static class Options {

    }

    public static void main(String[] args) {
        NApplication.builder(args).run();
    }

    @NAppRun
    public void run() {
        NRef<String> command = NRef.ofNull();
        List<String> args2 = new ArrayList<>();
        Options o = new Options();
        NCmdLine cmd = NApplication.of().cmdLine();

        // Manual split: first known non-option token is the command, everything else
        // (including options like `clean --simple`) is forwarded to the sub-command.
        // NSession global options (e.g. -y, --bot, --dry) are consumed first.
        while (cmd.hasNext()) {
            if (NSession.of().configureFirst(cmd)) {
                continue;
            }
            if (command.isNull()) {
                NOptional<NArg> p = cmd.peek();
                if (p.isPresent() && p.get().isNonOption()) {
                    String norm = normalizeCommand(p.get().image());
                    if (norm != null) {
                        cmd.next();
                        command.set(norm);
                        continue;
                    }
                    command.set("default");
                } else if (p.isPresent() && p.get().isOption()) {
                    // Option before any command (e.g. `nmvn --dry clean` where --dry
                    // was not a session option). Keep command unresolved and forward it.
                    args2.add(cmd.next().get().image());
                    continue;
                } else {
                    break;
                }
            }
            args2.add(cmd.next().get().image());
        }

        if (command.isNull()) {
            command.set(args2.isEmpty() ? "build" : "default");
        }
        if (cmd.isExecMode()) {
            MavenCliWrapper cli = new MavenCliWrapper();

            String[] args2Arr = args2.toArray(new String[0]);
            switch (command.get()) {
                case "build":
                case "default": {
                    List<String> defaultArgs = new ArrayList<>();
                    for (String ar : args2Arr) {
                        if (ar.startsWith("-D")) {
                            String[] as = ar.substring(2).split("=");
                            cli.setProperty(as[0], as[1]);
                        } else {
                            defaultArgs.add(ar);
                        }
                    }
                    int r = callMvn(cli, o, defaultArgs.toArray(new String[0]));
                    if (r == NExecutionException.SUCCESS) {
                        return;
                    } else {
                        throw new NExecutionException(NMsg.ofC("Maven Call exited with code %d", r), r);
                    }
                }
                case "get": {
                    cli.setArtifactId(args2Arr[0]);
                    String repo = null;
                    if (args2Arr.length > 1) {
                        repo = args2Arr[1];
                    }
                    if ("central".equals(repo)) {
                        repo = null;
                    }
                    if ("vpc-public-maven".equals(repo)) {
                        repo = "https://raw.github.com/thevpc/vpc-public-maven/master";
                    }
                    if (repo != null) {
                        cli.setRepoUrl(repo);
                    }
                    NPath dir = createTempPom();
                    cli.setWorkingDirectory(dir.toString());
                    int r = callMvn(cli, o, "dependency:get");
                    dir.delete(true);
                    if (r == NExecutionException.SUCCESS) {
                        return;
                    } else {
                        throw new NExecutionException(NMsg.ofC("Maven Call exited with code %s", r), r);
                    }
                }
                case "version": {
                    MvnVersionSubCommand versionCli = new MvnVersionSubCommand();
                    int r = versionCli.run(args2Arr);
                    if (r == NExecutionException.SUCCESS) {
                        return;
                    } else {
                        throw new NExecutionException(NMsg.ofC("Version command failed with code %s", r), r);
                    }
                }
                case "workset":
                case "config": {
                    MvnWorksetSubCommand worksetCli = new MvnWorksetSubCommand();
                    int r = worksetCli.run(args2Arr);
                    if (r == NExecutionException.SUCCESS) {
                        return;
                    } else {
                        throw new NExecutionException(NMsg.ofC("Workset command failed with code %s", r), r);
                    }
                }
                case "clean": {
                    MvnCleanSubCommand cleanCli = new MvnCleanSubCommand();
                    int r = cleanCli.run(args2Arr);
                    if (r == NExecutionException.SUCCESS) {
                        return;
                    } else {
                        throw new NExecutionException(NMsg.ofC("Clean command failed with code %s", r), r);
                    }
                }
                case "diff-jar": {
                    MvnJarCompareSubCommand diffJarCli = new MvnJarCompareSubCommand();
                    int r = diffJarCli.run(args2Arr);
                    if (r == NExecutionException.SUCCESS) {
                    } else {
                        throw new NExecutionException(NMsg.ofC("Diff jar command failed with code %s", r), r);
                    }
                }
            }
        }
    }

    private static String normalizeCommand(String img) {
        if (img == null) {
            return null;
        }
        switch (img) {
            case "build":
                return "build";
            case "get":
                return "get";
            case "version":
                return "version";
            case "workset":
            case "ws":
            case "config":
                return "workset";
            case "clean":
                return "clean";
            case "diff-jar":
                return "diff-jar";
            default:
                return null;
        }
    }

    private static int callMvn(MavenCliWrapper cli, Options options, String... args) {
        if (!NOut.isPlain()) {
            try {
                cli.setGrabString(true);
                int r = cli.doMain(args);
                String s = cli.getResultString();
                if (s.contains("BUILD SUCCESS")) {
                    NOut.println("{'result':'success'}");
                    return 0;
                } else {
                    if (r == 0) {
                        r = 1;
                    }
                    NOut.println("{'result':'error'}");
                }
                return r;
            } catch (Exception ex) {
                LOG.log(Level.FINE, "error executing mvn command " + Arrays.toString(args), ex);//e.printStackTrace();
                NOut.println("{'result':'error'}");
                return 1;
            }
        } else {
            return cli.doMain(args);
        }
    }

    private static NPath createTempPom() {
        NPath d = NPath.ofTempFolder();
        d.resolve("pom.xml").writeString("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"http://maven.apache.org/POM/4.0.0\"\n"
                + "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
                + "    <modelVersion>4.0.0</modelVersion>\n"
                + "    <groupId>temp</groupId>\n"
                + "    <artifactId>temp-nuts</artifactId>\n"
                + "    <version>1.0.0</version>\n"
                + "    <packaging>jar</packaging>\n"
                + "    <properties>\n"
                + "        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n"
                + "        <maven.compiler.source>1.8</maven.compiler.source>\n"
                + "        <maven.compiler.target>1.8</maven.compiler.target>\n"
                + "    </properties>\n"
                + "    <dependencies>\n"
                + "    </dependencies>\n"
                + "    <repositories>\n"
                + "        <repository>\n"
                + "            <id>vpc-public-maven</id>\n"
                + "            <url>https://raw.github.com/thevpc/vpc-public-maven/master</url>\n"
                + "            <snapshots>\n"
                + "                <enabled>true</enabled>\n"
                + "                <updatePolicy>always</updatePolicy>\n"
                + "            </snapshots>\n"
                + "        </repository>\n"
                + "    </repositories>\n"
                + "    <pluginRepositories>\n"
                + "        <pluginRepository>\n"
                + "            <id>vpc-public-maven</id>\n"
                + "            <url>https://raw.github.com/thevpc/vpc-public-maven/master</url>\n"
                + "            <snapshots>\n"
                + "                <enabled>true</enabled>\n"
                + "                <updatePolicy>always</updatePolicy>\n"
                + "            </snapshots>\n"
                + "        </pluginRepository>\n"
                + "    </pluginRepositories>\n"
                + "</project>\n");
        return d;
    }

}
