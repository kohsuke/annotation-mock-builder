package org.jvnet.annotation_mock_builder;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;

/**
 * Represents the configuration for {@link Artifact}.
 *
 * <p>
 * Ideally I should be able to let Maven fill in a real {@link Artifact}
 * for me, but couldn't figure out how to make that work.
 *
 * @author Kohsuke Kawaguchi
 */
public class ArtifactInfo {
    private String groupId;
    private String artifactId;
    private String version;

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Artifact toArtifact(ArtifactFactory factory) {
        return factory.createArtifact(groupId,artifactId,version,null,"jar");
    }
}
