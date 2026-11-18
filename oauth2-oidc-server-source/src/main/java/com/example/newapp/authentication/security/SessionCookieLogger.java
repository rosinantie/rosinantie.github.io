package com.example.newapp.authentication.security;

import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Spring Boot auto-registers any bean implementing a Servlet listener interface
 * with the embedded Tomcat server. sessionCreated/Destroyed fire the exact moment
 * Tomcat creates (or destroys) an HttpSession - the same moment it puts a JSESSIONID
 * cookie in the response (or tells the browser to drop it). attributeAdded fires the
 * exact moment Spring Security WRITES your Authentication (principal + authorities)
 * or the CSRF token into that session - this is the only way to actually see the
 * contents of the in-memory session "table", since there is no DB/Redis backing it
 * in this app (plain Tomcat heap memory - nothing to query from outside the JVM).
 */
@Slf4j
@Component
public class SessionCookieLogger implements HttpSessionListener, HttpSessionAttributeListener {

    @Override
    public void sessionCreated(HttpSessionEvent event) {
        log.info("SESSION CREATED id='{}' - this exact value is what goes out as the JSESSIONID cookie",
                event.getSession().getId());
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        log.info("SESSION DESTROYED id='{}' (logout, timeout, or server restart)",
                event.getSession().getId());
    }

    @Override
    public void attributeAdded(HttpSessionBindingEvent event) {
        log.info("SESSION WRITE sessionId='{}' key='{}' value={}",
                event.getSession().getId(), event.getName(), event.getValue());
    }

    @Override
    public void attributeReplaced(HttpSessionBindingEvent event) {
        log.info("SESSION OVERWRITE sessionId='{}' key='{}' newValue={}",
                event.getSession().getId(), event.getName(), event.getValue());
    }
}
