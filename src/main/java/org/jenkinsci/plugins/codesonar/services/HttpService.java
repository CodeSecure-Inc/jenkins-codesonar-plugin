package org.jenkinsci.plugins.codesonar.services;

import hudson.AbortException;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.StatusLine;
import org.apache.http.client.CookieStore;
import org.apache.http.client.fluent.Executor;
import org.apache.http.client.fluent.Form;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.BasicCookieStore;

/**
 *
 * @author Andrius
 */
public class HttpService implements Serializable {

    private CookieStore httpCookieStore;
    private Executor executor;

    public HttpService() {
        httpCookieStore = new BasicCookieStore();
        executor = Executor.newInstance().use(httpCookieStore);
    }

    private static final Logger logger = Logger.getLogger(HttpService.class.getName());

    public String getContentFromUrlAsString(URI uri) throws AbortException {
        return getContentFromUrlAsString(uri.toString());
    }
    
    public String getContentFromUrlAsString(String url) throws AbortException {
        logger.fine(String.format("Request sent to %s", url));
        String output;
        try {
            output = executor.execute(Request.Get(url)).returnContent().asString();
        } catch (Exception e) {
            logger.log(Level.SEVERE, String.format("[CodeSonar] Error on url: %s", url), e);
            throw new AbortException(String.format("[CodeSonar] Error on url: %s%n[CodeSonar] Message is: %s", url, e.getMessage()));
        }

        return output;
    }

    public void authenticate(URI uri, String username, String password) throws AbortException {
        List<NameValuePair> loginForm = Form.form()
                .add("sif_username", username)
                .add("sif_password", password)
                .add("sif_sign_in", "yes")
                .add("sif_log_out_competitor", "yes")
                .build();

        try {
            URIBuilder uriBuilder = new URIBuilder(uri);
            uriBuilder.setPath("/sign_in.html");
            uri = uriBuilder.build();

            HttpResponse resp = executor
                    .execute(Request.Post(uri)
                    .bodyForm(loginForm))
                    .returnResponse();
            
            int statusCode = resp.getStatusLine().getStatusCode();

            if (statusCode != 200) {
                throw new AbortException("[CodeSonar] failed to authenticate.");
            }
        } catch (URISyntaxException | IOException e) {
            throw new AbortException(String.format("[CodeSonar] Error on url: %s%n[CodeSonar] Message is: %s", e.getMessage()));
        }
    }
}
