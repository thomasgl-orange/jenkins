package jenkins.slaves;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.ServletException;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SingleFileSCM;
import org.jvnet.hudson.test.ToolInstallations;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.maven.MavenModuleSet;
import hudson.maven.MavenModuleSetBuild;
import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import hudson.slaves.DumbSlave;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * This is not a unit test, but more a test procedure to hopefully trigger some remote class loader corruption as
 * described in JENKINS-61103.
 * <ul>
 * <li>launch, and then interrupt, a simple Maven job</li>
 * <li>look for a LinkageError in the agent log</li>
 * <li>launch the job again if there is one (to confirm the suspected class loader corruption)</li>
 * </ul>
 * The test is repeated with different timings for build interruption, to hopefully hit a phase of remote class loading.
 */
@RunWith(Parameterized.class)
public class AbortedMavenProjectOnAgentTest {

    private static final long MIN_SLEEP = 800;
    private static final long MAX_SLEEP = 1600;
    private static final long FIXED_SLEEP_INC = 10; // alternative is SLEEP_INC_PERCENT

    private static final long SLEEP_INC_PERCENT = 0; // set to 0 for using FIXED_SLEEP_INC
    private static final long MAX_SLEEP_INC = 500;

    @Parameters(name = "{index}: {0}ms")
    public static Collection<Object[]> parameters() {
        Collection<Object[]> params = new ArrayList<>();
        for (long p = MIN_SLEEP ; p <= MAX_SLEEP ; p = increase(p)) {
          params.add( new Long[] { p } );
        }
        return params;
    }

    private static long increase(long p) {
        if (p <= 0) {
            return 1;
        }
        long inc = SLEEP_INC_PERCENT <= 0
                ? FIXED_SLEEP_INC // increase by a fixed number of ms
                : Math.min(p * SLEEP_INC_PERCENT / 100, MAX_SLEEP_INC); // increase by a percentage, with an upper bound
        return p + inc;
    }

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private DumbSlave node;

    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    private final long sleepTimeInMs;

    // used to ignore test parameters above the first "too high" value
    private static final AtomicLong maxUsefulSleep = new AtomicLong(Long.MAX_VALUE);

    public AbortedMavenProjectOnAgentTest(@NonNull Long sleepTimeInMs) {
        this.sleepTimeInMs = sleepTimeInMs;
    }

    @Before
    public void before() throws Exception {
        // skip test if the sleep time parameter is too high
        long maxSleep = maxUsefulSleep.get();
        assumeTrue("requested sleep time (" + sleepTimeInMs + "ms) is longer than the known upper bound: "
                + maxSleep + "ms", sleepTimeInMs <= maxSleep);

        // make sure we execute jobs via remoting
        node = j.createOnlineSlave();
        node.setLabelString("maven");
        j.getInstance().setNumExecutors(0);

        // we also need a Maven installation
        ToolInstallations.configureDefaultMaven();
    }

    @After
    public void after() throws Exception {
        executor.shutdown();
    }

    private MavenModuleSet createMavenProject() throws IOException {
        final MavenModuleSet mavenProject = j.jenkins.createProject(MavenModuleSet.class, "p");
        mavenProject.setAssignedLabel(j.jenkins.getLabel("maven"));
        mavenProject.setIsArchivingDisabled(true);
        mavenProject.setIsFingerprintingDisabled(true);
        mavenProject.setIsSiteArchivingDisabled(true);
        mavenProject.setQuietPeriod(0);
        mavenProject.setScm(new SingleFileSCM("pom.xml", ""
                + "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.mycompany.app</groupId>\n"
                + "  <artifactId>my-app</artifactId>\n"
                + "  <version>1</version>\n"
                + "</project>\n"));
        mavenProject.setGoals("clean");
        return mavenProject;
    }

    @Test
    public void testDelayedAbortOnMavenProject() throws Exception {
        final MavenModuleSet mavenProject = createMavenProject();

        // launch a first build
        QueueTaskFuture<MavenModuleSetBuild> futureBuild = mavenProject.scheduleBuild2(0);
        MavenModuleSetBuild startedBuild = futureBuild.waitForStart();
        Future<Exception> result = executor.submit(() -> {
            try {
                Thread.sleep(sleepTimeInMs);
                if (startedBuild.isBuilding()) {
                    startedBuild.doStop();
                    //System.out.println(">> Aborted build after " + sleepTimeInMs + "ms");
                    return (Exception) null; // okay, we have interrupted the build
                }
                //System.out.println(">> Too late to abort build after " + sleepTimeInMs + "ms");
                return new TimeoutException("too late, job was not building anymore");
            } catch (InterruptedException e) {
                //System.out.println(">> Interrupted while sleeping for " + sleepTimeInMs + "ms");
                return e;
            } catch (IOException | ServletException e) {
                //System.out.println(">> Failed to abort build after " + sleepTimeInMs + "ms");
                return e;
            }
        });

        // wait for end of build
        MavenModuleSetBuild terminatedBuild = futureBuild.get();

        // display build log
        //System.out.println(JenkinsRule.getLog(terminatedBuild));

        // how did the abort go?
        final Exception abortException = result.get();

        // stop here if build was not aborted
        if (abortException instanceof TimeoutException) {
            // too late, the build is finished (in SUCCESS)
            maxUsefulSleep.getAndUpdate(v -> (sleepTimeInMs < v) ? sleepTimeInMs : v); // update sleep upper bound
            results.add(new TestResult(sleepTimeInMs, terminatedBuild.getDuration(),
                    terminatedBuild.getResult(), false));
            j.assertBuildStatus(Result.SUCCESS, terminatedBuild);
            return;
        } else if (abortException != null) {
            // unexpected exception from the abortion thread
            throw abortException;
        }

        // did we get a class loading error on aborted build?
        final boolean gotLinkageError;

        // check result is ABORTED
        /* j.assertBuildStatus(Result.ABORTED, terminatedBuild); */
        // Actually, no, sometimes the InterruptedException leads to a FAILURE.
        // Which is an issue on its own, but not the one we're tracking here...

        // look for class loading exceptions in the agent log
        String agentLog = node.toComputer().getLog();
        gotLinkageError = agentLog.contains("LinkageError");

        // keep some stats
        results.add(new TestResult(sleepTimeInMs, terminatedBuild.getDuration(),
                terminatedBuild.getResult(), gotLinkageError));

        // first build was ABORTED, let's try again and check it can still succeed
        if (gotLinkageError) { // only do when we suspect an unrecoverable class loading error
            futureBuild = mavenProject.scheduleBuild2(0);
            terminatedBuild = futureBuild.get();
            String msg = "unexpected build status;\n"
                    + "build log was:"
                    + "\n------\n"
                    + JenkinsRule.getLog(terminatedBuild)
                    + "\n------\n"
                    + "agent log was:"
                    + "\n------\n"
                    + node.toComputer().getLog()
                    + "\n------\n";
            assertThat(msg, terminatedBuild.getResult(), is(Result.SUCCESS));
        }
    }

    // keep track of the test builds
    private static final ConcurrentLinkedQueue<TestResult> results = new ConcurrentLinkedQueue<>();

    private static final class TestResult implements Comparable<TestResult> {
        private final long sleepTime;
        private final long firstBuildTime;
        private final Result firstBuildResult;
        private final boolean gotLinkageError;

        private TestResult(long sleepTime, long firstBuildTime, Result firstBuildResult, boolean gotLinkageError) {
            this.sleepTime = sleepTime;
            this.firstBuildTime = firstBuildTime;
            this.firstBuildResult = firstBuildResult;
            this.gotLinkageError = gotLinkageError;
        }

        @Override
        public String toString() {
            return String.format(
                    "TestResult [sleepTime=%s, firstBuildTime=%s, firstBuildResult=%s, gotLinkageError=%s]",
                    sleepTime, firstBuildTime, firstBuildResult, gotLinkageError);
        }

        @Override
        public int compareTo(TestResult o) {
            return Long.compare(this.sleepTime, o.sleepTime);
        }
    }

    @AfterClass
    public static void afterClass() {
        System.out.println("====== Aborted builds summary:");
        results.stream().sorted().forEach(System.out::println);
    }

}
