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
import net.thevpc.nuts.util.NRef;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@NApp
public class NMvnMain  {
    private static final Logger LOG= Logger.getLogger(NMvnMain.class.getName());
//    public static void main(String[] args) {
//        main0(new String[]{
//                "--json", "--get", "test:classpath:1.3", "vpc-public-maven"
//        });
//    }

    public static class Options {

        boolean json = false;

    }

    public static void main(String[] args) {
        NApplication.builder(args).run();
    }

    @NAppRun
    public void run() {
        NRef<String> command = NRef.ofNull();
        List<String> args2 = new ArrayList<>();
        Options o = new Options();
        NSession session = NSession.of();
        NCmdLine cmd = NApplication.of().cmdLine();
        while (cmd.hasNext()) {
            if (command.isNull()) {
                if (session.configureFirst(cmd)) {
                    // handled by nuts
                } else if (!cmd.matcher()
                        .when("-j", "--json").asFlag(a -> o.json = a.booleanValue())
                        .when("build").asArg(a -> command.set("build"))
                        .when("get").asArg(a -> command.set("get"))
                        .when("version").asArg(a -> command.set("version"))
                        .when("workset", "ws", "config").asArg(a -> command.set("workset"))
                        .anyMatch()) {
                    command.set("default");
                    args2.add(cmd.next().get().image());
                }
            } else {
                args2.add(cmd.next().get().image());
            }
        }
        if (command.isNull()) {
            command.set("build");
        }
        if (cmd.isExecMode()) {
            MavenCli2 cli = new MavenCli2(session);

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
                    int r = callMvn(cli,session, o, defaultArgs.toArray(new String[0]));
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
                    NPath dir = createTempPom(session);
                    cli.setWorkingDirectory(dir.toString());
                    int r = callMvn(cli,session, o,  "dependency:get");
                    dir.delete(true);
                    if (r == NExecutionException.SUCCESS) {
                        return;
                    } else {
                        throw new NExecutionException(NMsg.ofC("Maven Call exited with code %s", r), r);
                    }
                }
                case "version": {
                    MvnVersionCli versionCli = new MvnVersionCli(session);
                    int r = versionCli.run(args2Arr, o.json);
                    if (r == NExecutionException.SUCCESS) {
                        return;
                    } else {
                        throw new NExecutionException(NMsg.ofC("Version command failed with code %s", r), r);
                    }
                }
                case "workset":
                case "config": {
                    MvnWorksetCli worksetCli = new MvnWorksetCli(session);
                    int r = worksetCli.run(args2Arr, o.json);
                    if (r == NExecutionException.SUCCESS) {
                        return;
                    } else {
                        throw new NExecutionException(NMsg.ofC("Workset command failed with code %s", r), r);
                    }
                }
            }
        }
    }

//    public void prepareM2Home(NSession session){
//        Path configFolder = session.getConfigFolder();
//        if(!Files.isRegularFile(configFolder.resolve(".mvn/maven.config"))){
//            if(!Files.isDirectory(configFolder.resolve(".mvn"))){
//                Files.createDirectories(configFolder.resolve(".mvn"));
//            }
//            Files.
//        }
//        maven.multiModuleProjectDirectory
//    }
    private static int callMvn(MavenCli2 cli, NSession session, Options options, String... args) {
       if (options.json) {
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
                LOG.log(Level.FINE,"error executing mvn command "+ Arrays.toString(args),ex);//e.printStackTrace();
                session.out().println("{'result':'error'}");
                return 1;
            }
        } else {
            return cli.doMain(args);
        }
    }

    private static NPath createTempPom(NSession session) {
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

    public static int[] delete(NPath file) {
        file.delete(true);
        return new int[]{1, 0};
    }
}
