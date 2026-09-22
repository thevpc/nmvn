package net.thevpc.nuts.toolbox.mvn.util;

import net.thevpc.nuts.app.NApplication;
import net.thevpc.nuts.io.NErr;
import net.thevpc.nuts.io.NOut;
import org.apache.maven.cli.MavenCli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

public class MavenCliWrapper {
    ByteArrayOutputStream bos;
    private String workingDirectory;
    private String multiModuleProjectDirectory;
    private String artifactId;
    private String repoUrl;
    private boolean grabString;
    private Map<String, String> options = new HashMap<>();


    public MavenCliWrapper() {
    }

    public boolean isGrabString() {
        return grabString;
    }

    public MavenCliWrapper setGrabString(boolean grabString) {
        this.grabString = grabString;
        return this;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public MavenCliWrapper setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
        return this;
    }

    public String getMultiModuleProjectDirectory() {
        return multiModuleProjectDirectory;
    }

    public MavenCliWrapper setMultiModuleProjectDirectory(String multiModuleProjectDirectory) {
        this.multiModuleProjectDirectory = multiModuleProjectDirectory;
        return this;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public MavenCliWrapper setArtifactId(String artifactId) {
        this.artifactId = artifactId;
        return this;
    }

    public String getRepoUrl() {
        return repoUrl;
    }

    public MavenCliWrapper setRepoUrl(String repoUrl) {
        this.repoUrl = repoUrl;
        return this;
    }

    public int doMain(String[] args) {
        if (multiModuleProjectDirectory == null) {
            System.setProperty("maven.multiModuleProjectDirectory", NApplication.of().confFolder().toString());
        } else {
            System.setProperty("maven.multiModuleProjectDirectory", multiModuleProjectDirectory);
        }
        if (artifactId != null) {
            System.setProperty("artifact", artifactId.replaceFirst("#", ":"));
        }
        for (Map.Entry<String, String> ss : options.entrySet()) {
            System.setProperty(ss.getKey(), ss.getValue());
        }
        MavenCli cli = new MavenCli();
        String wd = this.workingDirectory;
        if(wd==null){
            wd=".";
        }
        if (grabString) {
            bos = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(bos);
            int t = cli.doMain(args, wd, out, out);
            out.flush();
            return t;
        } else {
            return cli.doMain(args, wd, NOut.asPrintStream(), NErr.asPrintStream());
        }
    }

    public String getResultString(){
        return bos.toString();
    }

    public void setProperty(String a, String a1) {
        options.put(a, a1);
    }
}
