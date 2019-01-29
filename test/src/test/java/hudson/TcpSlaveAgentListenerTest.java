package hudson;

import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.TextPage;

import java.net.URL;

import jenkins.model.Jenkins;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsRule.WebClient;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;

public class TcpSlaveAgentListenerTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void headers() throws Exception {
        WebClient wc = r.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false);

        r.getInstance().setSlaveAgentPort(-1);
        Page p = wc.goTo("tcpSlaveAgentListener");
        assertThat(p.getWebResponse().getStatusCode(), equalTo(404));

        r.getInstance().setSlaveAgentPort(0);
        p = wc.goTo("tcpSlaveAgentListener", "text/plain");
        assertThat(p.getWebResponse().getStatusCode(), equalTo(200));
        assertThat(p.getWebResponse().getResponseHeaderValue("X-Instance-Identity"), notNullValue());
    }

    @Test
    public void diagnostics() throws Exception {
        r.getInstance().setSlaveAgentPort(0);
        int p = r.jenkins.getTcpSlaveAgentListener().getPort();
        WebClient wc = r.createWebClient();

        TextPage text = wc.getPage(new URL("http://localhost:" + p + "/"));
        String c = text.getContent();
        assertThat(c, containsString(Jenkins.VERSION));

        wc.setThrowExceptionOnFailingStatusCode(false);
        Page page = wc.getPage(new URL("http://localhost:" + p + "/xxx"));
        assertThat(page.getWebResponse().getStatusCode(), equalTo(404));
    }
}
