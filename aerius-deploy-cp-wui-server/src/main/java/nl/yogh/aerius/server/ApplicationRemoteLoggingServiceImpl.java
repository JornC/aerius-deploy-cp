package nl.yogh.aerius.server;

import javax.servlet.annotation.WebServlet;

import com.google.gwt.logging.server.RemoteLoggingServiceImpl;

/**
 * Wrapper class for GWT remote logging that adds an annotation so we don't have to add the servlet configuration to the web.xml.
 */
@WebServlet("/application/remote_logging")
public class ApplicationRemoteLoggingServiceImpl extends RemoteLoggingServiceImpl {

  private static final long serialVersionUID = 4826206602229801819L;
}
