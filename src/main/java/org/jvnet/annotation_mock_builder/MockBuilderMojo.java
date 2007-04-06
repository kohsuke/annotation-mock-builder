package org.jvnet.annotation_mock_builder;

import com.sun.codemodel.ClassType;
import com.sun.codemodel.JClassAlreadyExistsException;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JPackage;
import com.sun.codemodel.JVar;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JExpression;
import com.sun.codemodel.JOp;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Creates a glassfish distribution image.
 *
 * @goal build-mocks
 * @phase generate-sources
 * @requiresProject
 *
 * @author Kohsuke Kawaguchi
 */
public class MockBuilderMojo extends AbstractMojo {
    /**
     * Jar in which annotations are searched.
     *
     * @parameter
     */
    public ArtifactInfo jar;

    /**
     * Patterns for narrowing down what classes to process.
     */
    public Classes[] patterns;

    /**
     * @parameter expression="${localRepository}"
     * @required
     */
    public ArtifactRepository localRepository;

    /**
     * @component
     */
    public ArtifactFactory artifactFactory;

    /**
     * @component
     */
    public ArtifactResolver artifactResolver;

    /**
     * The maven project.
     *
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    public MavenProject project;

    public ClassLoader userLoader;

    public final Map<Class,JDefinedClass> queue = new HashMap<Class,JDefinedClass>();

    /**
     * Generated interfaces go into this codeModel.
     */
    public final JCodeModel codeModel = new JCodeModel();

    /**
     * The writers will be generated into this package.
     */
    public JPackage pkg;

    /**
     * Output directory
     * @parameter default-value="target/annotation-mocks"
     */
    public File outputDirectory;

    /**
     * @parameter
     */
    public String packageName="";

    public void execute() throws MojoExecutionException, MojoFailureException {
        this.pkg = codeModel._package(packageName);

        File jarFile;
        try {
            Artifact artifact = jar.toArtifact(artifactFactory);
            artifactResolver.resolve(artifact,
                project.getRemoteArtifactRepositories(), localRepository);
            jarFile = artifact.getFile();
        } catch (ArtifactResolutionException e1) {
            throw new MojoExecutionException("Error attempting to download the distribution POM", e1);
        } catch (ArtifactNotFoundException e11) {
            throw new MojoExecutionException("Distribution POM not found", e11);
        }

        try {
            userLoader = new URLClassLoader(new URL[]{jarFile.toURL()},getClass().getClassLoader());
        } catch (MalformedURLException e) {
            throw new MojoExecutionException("Unable to convert "+jarFile+" to URL",e);
        }

        processJar(jarFile);

        // actually generate beans
        for( Map.Entry<Class,JDefinedClass> e : queue.entrySet() ) {
            Class ann = e.getKey();
            JDefinedClass w = e.getValue();

            w._implements(ann);

            w.method(JMod.PUBLIC, codeModel.ref(Class.class).narrow(ann), "annotationType")
                .body()._return(codeModel.ref(ann).dotclass());

            boolean hadParameter = false;

            // fill-all constructor
            JMethod ctr = w.constructor(JMod.PUBLIC);

            JMethod $equals = w.method(JMod.PUBLIC,boolean.class,"equals");
            JVar $that = $equals.param(Object.class, "that");
            $equals.body()._if($that._instanceof(codeModel.ref(ann)).not())._then()._return(JExpr.lit(false));

            JMethod $hashCode = w.method(JMod.PUBLIC,int.class,"hashCode");
            JVar $r = $hashCode.body().decl(codeModel.INT, "r", JExpr.lit(0));

            // define property
            for( Method m : ann.getDeclaredMethods() ) {
                hadParameter = true;
                Class rt = m.getReturnType();

                // field
                JVar $field = w.field(JMod.PRIVATE,rt,m.getName());

                // setter
                JMethod $set = w.method(JMod.PUBLIC, void.class, m.getName());
                $set.body().assign($field,$set.param(rt,"value"));

                // getter
                w.method(JMod.PUBLIC, rt, m.getName()).body()._return($field);

                // assignment in the constructor
                ctr.body().assign(JExpr._this().ref($field),ctr.param(rt,m.getName()));

                // equals
                $equals.body()._if(eq(rt,$field,
                    ((JExpression)JExpr.cast(w,$that)).ref($field)).not())._then()._return(JExpr.lit(false));

                // hashCode
                $hashCode.body().assign($r,$r.xor(hashCodeOp(rt,$field)));
            }

            $equals.body()._return(JExpr.lit(true));
            $hashCode.body()._return($r);

            // default constructor
            if(hadParameter)
                w.constructor(JMod.PUBLIC);
        }


        try {
            outputDirectory.mkdirs();
            codeModel.build(outputDirectory);
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to queue code to "+outputDirectory,e);
        }

        project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
    }

    private JExpression hashCodeOp(Class rt, JVar $field) {
        if(rt==int.class || rt==short.class || rt==long.class || rt==char.class)
            return JExpr.cast(codeModel.INT,$field);
        if(rt==boolean.class)
            return JOp.cond($field,JExpr.lit($field.name().hashCode()),JExpr.lit(0));
        if(rt==String.class || rt==Class.class)
            return $field.invoke("hashCode");
        // lazy
        return JExpr.lit(0);
    }

    private JExpression eq(Class type,JExpression lhs, JExpression rhs) {
        if(type.isPrimitive())
            return lhs.eq(rhs);
        else
            return lhs.invoke("equals").arg(rhs);
    }

    /**
     * Visits a jar fil and looks for classes that match the specified pattern.
     */
    private void processJar(File jarfile) throws MojoExecutionException {
        try {
            JarFile jar = new JarFile(jarfile);
            for( Enumeration<JarEntry> en = jar.entries(); en.hasMoreElements(); ) {
                JarEntry e = en.nextElement();
                process(e.getName(),e.getTime());
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to process "+jarfile,e);
        }
    }

    /**
     * Process a file.
     *
     * @param name such as "javax/xml/bind/Abc.class"
     */
    private void process(String name,long timestamp) throws MojoExecutionException {
        if(!name.endsWith(".class"))
            return; // not a class
        name = name.substring(0,name.length()-6);
        name = name.replace('/','.'); // make it a class naem

        if(patterns==null || patterns.length==0) {
            queue(name,timestamp);
            return;
        }

        // find a match
        for( Classes c : patterns )
            if(c.include.matcher(name).matches()) {
                if(c.exclude!=null && c.exclude.matcher(name).matches())
                    continue;

                queue(name,timestamp);
                return;
            }
    }

    /**
     * Queues a file for generation.
     */
    private void queue(String className, long timestamp) throws MojoExecutionException {
        getLog().debug("Processing "+className);
        Class ann;
        try {
            ann = userLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new MojoExecutionException("No such class found: "+className,e);
        }

        if(!Annotation.class.isAssignableFrom(ann)) {
            getLog().debug("Skipping "+className+". Not an annotation");
            return;
        }

        JDefinedClass w;
        try {
            w = pkg._class(JMod.PUBLIC,getShortName(className)+"Bean", ClassType.CLASS);
        } catch (JClassAlreadyExistsException e) {
            throw new MojoExecutionException("Class name collision on "+className, e);
        }

        // up to date check
        String name = pkg.name();
        if(name.length()==0)    name = getShortName(className);
        else                    name += '.'+getShortName(className);

        File dst = new File(outputDirectory,name.replace('.',File.separatorChar)+"Writer.java");
        if(dst.exists() && dst.lastModified() > timestamp ) {
            getLog().debug("Skipping "+className+". Up to date.");
            w.hide();
        }

        queue.put(ann,w);
    }

    /**
     * Gets the short name from a fully-qualified name.
     */
    private static String getShortName(String className) {
        int idx = className.lastIndexOf('.');
        if(idx<0)   return className;
        else        return className.substring(idx+1);
    }
}
