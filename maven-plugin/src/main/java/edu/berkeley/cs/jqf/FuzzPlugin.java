/*
 * Copyright (c) 2018, University of California, Berkeley
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package edu.berkeley.cs.jqf;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.util.List;

import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndexingGuidance;
import edu.berkeley.cs.jqf.fuzz.guidance.Guidance;
import edu.berkeley.cs.jqf.fuzz.junit.GuidedFuzzing;
import edu.berkeley.cs.jqf.instrument.InstrumentingClassLoader;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * The main Mojo for the JQF Maven plugin.
 *
 * <p>This is the entry point for fuzzing via Maven.</p>
 *
 * <p>Enables "mvn jqf:fuzz".</p>
 *
 * @author Rohan Padhye
 */
@Mojo(name="fuzz",
        requiresDependencyResolution= ResolutionScope.TEST,
        defaultPhase=LifecyclePhase.VERIFY)
public class FuzzPlugin extends AbstractMojo {

    @Parameter(defaultValue="${project}", required=true, readonly=true)
    MavenProject project;

    @Parameter(defaultValue="${project.build.directory}", readonly=true)
    private File target;

    /**
     * The fully-qualified name of the test class containing methods
     * to fuzz.
     *
     * <p>This class will be loaded using the Maven project's test
     * classpath. It must be annotated with {@code @RunWith(JQF.class}</p>
     */
    @Parameter(property="class", required=true)
    private String testClassName;

    /**
     * The name of the method to fuzz.
     *
     * <p>This method must be annotated with {@code @Fuzz}, and take
     * one or more arguments (with optional junit-quickcheck
     * annotations) whose values will be fuzzed by JQF.</p>
     *
     * <p>If more than one method of this name exists in the
     * test class or if the method is not declared
     * {@code public void}, then the fuzzer will not launch.</p>
     */
    @Parameter(property="method", required=true)
    private String testMethod;

    /**
     * Comma-separated list of FQN prefixes to exclude from
     * coverage instrumentation.
     *
     * <p>Example: <tt>org/mozilla/javascript/gen,org/slf4j/logger</tt>,
     * will exclude classes auto-generated by Mozilla Rhino's CodeGen and
     * logging classes.</p>
     */
    @Parameter(property="excludes")
    private String excludes;

    /**
     * Comma-separated list of FQN prefixes to forcibly include,
     * even if they match an exclude.
     *
     * <p>Typically, these will be a longer prefix than a prefix
     * in the excludes clauses.</p>
     */
    @Parameter(property="includes")
    private String includes;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {


        ClassLoader loader;
        Guidance guidance;
        PrintStream out = System.out; // TODO: Re-route to logger from super.getLog()

        // Configure classes to instrument
        if (excludes != null) {
            System.setProperty("janala.excludes", excludes);
        }
        if (includes != null) {
            System.setProperty("janala.includes", includes);
        }

        try {
            List<String> classpathElements = project.getTestClasspathElements();

            loader = new InstrumentingClassLoader(
                    classpathElements.toArray(new String[0]),
                    getClass().getClassLoader());
        } catch (DependencyResolutionRequiredException|MalformedURLException e) {
            throw new MojoExecutionException("Could not get project classpath", e);
        }

        try {
            File resultsDir = new File(target, "fuzz-results");
            guidance = new ExecutionIndexingGuidance(10_000, resultsDir);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not create output directory", e);
        }

        try {
            GuidedFuzzing.run(testClassName, testMethod, loader, guidance, out);
        } catch (ClassNotFoundException e) {
            throw new MojoExecutionException("Could not load test class", e);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException("Bad request", e);
        } catch (RuntimeException e) {
            throw new MojoExecutionException("Internal error", e);
        }
    }
}
