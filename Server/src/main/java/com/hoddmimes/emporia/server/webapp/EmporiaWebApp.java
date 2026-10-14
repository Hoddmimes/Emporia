package com.hoddmimes.emporia.server.webapp;

import com.hoddmimes.emporia.server.RestApi;
import com.hoddmimes.emporia.server.Server;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import jakarta.websocket.server.ServerContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Runs the server as a web application in Tomcat (the WAR's WEB-INF/web.xml declares this listener).
 * At deploy it starts the server (Server), registers the REST API as a servlet (/api/*, and /admin for
 * its redirect), the /ws WebSocket (BdxWebSocket) and a session listener that closes a timed-out session's
 * WebSockets. A filter on /ws gives the WebSocket handshake the client's address (see RemoteAddressFilter),
 * which a Jakarta WebSocket endpoint can't get otherwise. Tomcat serves the static pages from the WAR (the WebContent directory) and does HTTP,
 * HTTPS and ports; the configuration file's http_interface is not used.
 *
 * The configuration file and mode are taken from, in this order, the context parameter, the Java system
 * property or the environment variable: emporia.config / EMPORIA_CONFIG (required) and emporia.mode /
 * EMPORIA_MODE (normal or test, default normal). E.g. CATALINA_OPTS="-Demporia.config=/etc/emporia.json".
 * The pages use relative paths, so the application can be deployed at any context path, e.g. as
 * webapps/emporia.war at /emporia.
 */
public class EmporiaWebApp implements ServletContextListener {

    private static final Logger cLog = LogManager.getLogger(EmporiaWebApp.class);

    private Server                   mServer;
    private ScheduledExecutorService mPinger;

    @Override
    public void contextInitialized(ServletContextEvent pEvent) {
        ServletContext tContext = pEvent.getServletContext();
        try {
            String tConfigPath = setting(tContext, "emporia.config", "EMPORIA_CONFIG", null);
            String tMode       = setting(tContext, "emporia.mode",   "EMPORIA_MODE",   "normal");
            if (tConfigPath == null) {
                throw new IllegalStateException("No configuration file: set emporia.config (context parameter or "
                        + "-Demporia.config=<file>) or the environment variable EMPORIA_CONFIG");
            }
            if (!tMode.equals("normal") && !tMode.equals("test")) {
                throw new IllegalStateException("emporia.mode must be normal or test, not '" + tMode + "'");
            }

            mServer = new Server(Server.loadConfig(tConfigPath), tMode.equals("test"));
            RestApi tApi = mServer.restApi();

            ServletRegistration.Dynamic tServlet = tContext.addServlet("emporia-api", tApi.createServlet(tContext.getContextPath()));
            tServlet.addMapping("/api/*", "/admin", "/admin/");
            tServlet.setAsyncSupported(true);
            tServlet.setLoadOnStartup(1);

            tContext.addListener(new HttpSessionListener() {
                @Override
                public void sessionDestroyed(HttpSessionEvent pSessionEvent) {
                    mServer.bdx.closeClients(pSessionEvent.getSession().getId(), "Session expired");
                }
            });

            mPinger = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "WsPinger");
                t.setDaemon(true);
                return t;
            });
            // Before Tomcat's WebSocket filter (isMatchAfter false), which does the upgrade without passing it on
            tContext.addFilter("emporia-ws-remote-address", new RemoteAddressFilter())
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/ws");

            ServerContainer tWsContainer = (ServerContainer) tContext.getAttribute(ServerContainer.class.getName());
            if (tWsContainer == null) throw new IllegalStateException("The servlet container has no WebSocket support");
            tWsContainer.addEndpoint(BdxWebSocket.config(new BdxWebSocket(tApi.context(), mPinger)));

            cLog.info("Emporia web application started (mode {})", tMode);
        } catch (Exception e) {
            cLog.error("Emporia web application failed to start", e);
            throw new RuntimeException("Emporia failed to start: " + e.getMessage(), e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent pEvent) {
        if (mPinger != null) mPinger.shutdownNow();
        if (mServer != null) mServer.stop();
    }

    /**
     * Adds the header BdxWebSocket.REMOTE_ADDRESS_HEADER to the /ws upgrade request: the first
     * X-Forwarded-For address when the request came through a proxy (Apache), else the peer's address.
     * The wrapper replaces any such header sent by the client.
     */
    private static class RemoteAddressFilter implements Filter {
        @Override
        public void doFilter(ServletRequest pRequest, ServletResponse pResponse, FilterChain pChain)
                throws IOException, ServletException {
            if (!(pRequest instanceof HttpServletRequest tRequest)) { pChain.doFilter(pRequest, pResponse); return; }
            String tForwarded = tRequest.getHeader("X-Forwarded-For");
            String tAddress = (tForwarded != null && !tForwarded.isBlank())
                    ? tForwarded.split(",")[0].trim() : tRequest.getRemoteAddr();
            pChain.doFilter(new HttpServletRequestWrapper(tRequest) {
                private boolean ours(String pName) {
                    return BdxWebSocket.REMOTE_ADDRESS_HEADER.equalsIgnoreCase(pName);
                }

                @Override
                public String getHeader(String pName) {
                    return ours(pName) ? tAddress : super.getHeader(pName);
                }

                @Override
                public Enumeration<String> getHeaders(String pName) {
                    return ours(pName) ? Collections.enumeration(List.of(tAddress)) : super.getHeaders(pName);
                }

                @Override
                public Enumeration<String> getHeaderNames() {
                    List<String> tNames = new ArrayList<>();
                    for (String tName : Collections.list(super.getHeaderNames())) if (!ours(tName)) tNames.add(tName);
                    tNames.add(BdxWebSocket.REMOTE_ADDRESS_HEADER);
                    return Collections.enumeration(tNames);
                }
            }, pResponse);
        }
    }

    private static String setting(ServletContext pContext, String pName, String pEnvironment, String pDefault) {
        String tValue = pContext.getInitParameter(pName);
        if (tValue == null || tValue.isBlank()) tValue = System.getProperty(pName);
        if (tValue == null || tValue.isBlank()) tValue = System.getenv(pEnvironment);
        return (tValue == null || tValue.isBlank()) ? pDefault : tValue;
    }
}
