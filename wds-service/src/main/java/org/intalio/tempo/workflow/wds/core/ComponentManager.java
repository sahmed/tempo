/**
 * Copyright (c) 2008 Intalio inc.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * Intalio inc. - initial API and implementation
 */

package org.intalio.tempo.workflow.wds.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.intalio.tempo.deployment.ComponentId;
import org.intalio.tempo.deployment.DeploymentMessage;
import org.intalio.tempo.deployment.DeploymentMessage.Level;
import org.intalio.tempo.security.token.TokenContext;
import org.intalio.tempo.workflow.task.PIPATask;
import org.intalio.tempo.workflow.wds.core.xforms.XFormsProcessor;
import org.intalio.tempo.workflow.wds.servlets.PIPALoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ComponentManager implements org.intalio.tempo.deployment.spi.ComponentManager {
    private static final Logger LOG = LoggerFactory.getLogger(ComponentManager.class);

    private WDSServiceFactory _wdsFactory;

    public ComponentManager(WDSServiceFactory wdsFactory) {
        _wdsFactory = wdsFactory;
    }
    
    // ------------------ ComponentManager implementation ------------------------
    
    public String getComponentManagerName() {
        return "formmanager";
    }

    
    public void activate(ComponentId name, File path) {
        // nothing
    }

    public void deactivate(ComponentId name) {
        // nothing
    }

    public List<DeploymentMessage> deploy(ComponentId name, File path) {
        List<DeploymentMessage> msgs = new ArrayList<DeploymentMessage>();
        
        /* ALEX: Disabled until we get token propagation from deploy-impl
        if (!TokenContext.hasToken()) {
            msgs.add(new DeploymentMessage(Level.ERROR, "No security context token"));
            return msgs; 
        }
        */
        
        String token = TokenContext.getToken();

        WDSService wds = _wdsFactory.getWDSService();
        try {
            File[] files = path.listFiles();

            // Phase 1: Check for conflicts
            for (File f: files) {
                try {
                    DeploymentMessage msg = null;
                    InputStream input = new FileInputStream(f);
                    try {
                        if (f.getName().endsWith(".pipa")) {
                            msg = checkPipa(token, wds, input, relativePath(path, f));
                        } else if (f.getName().endsWith(".xform")) {
                            msg = checkXForm(token, wds, input, relativePath(path, f));
                        } else {
                            msg = checkItem(token, wds, input, relativePath(path, f));
                        }
                        if (msg != null) msgs.add(msg);
                    } finally {
                        close(input);
                    }
                } catch (Exception e) {
                    DeploymentMessage msg = new DeploymentMessage(Level.ERROR, e.toString());
                    msg.setResource(relativePath(path, f));
                    msgs.add(msg);
                }
            }

            // Stop if any error during checks
            for (DeploymentMessage msg: msgs) {
                if (Level.ERROR.equals(msg.getLevel())) return msgs;
            }
            
            // Phase 2: Actual deployment
            for (File f: files) {
                try {
                    DeploymentMessage msg = null;
                    InputStream input = new FileInputStream(f);
                    try {
                        if (f.getName().endsWith(".pipa")) {
                            msg = processPipa(token, wds, input, f.toString());
                        } else if (f.getName().endsWith(".xform")) {
                            msg = processXForm(token, wds, input, f.toString());
                        } else {
                            msg = processItem(token, wds, input, f.toString());
                        }
                        if (msg != null) msgs.add(msg);
                    } finally {
                        close(input);
                    }
                } catch (Exception e) {
                    // this shouldn't happen but if it does, fail fast
                    DeploymentMessage msg = new DeploymentMessage(Level.ERROR, e.toString());
                    msg.setResource(relativePath(path, f));
                    msgs.add(msg);
                    break;
                }
            }
        } finally {
            wds.close();
        }
        return msgs;
    }

    public void undeploy(ComponentId name) {
        // TODO: We need to record which PIPAs, XForms and Items were deployed for this component.
        
        // 
    }

    public void start(ComponentId name) {
        // nothing
    }

    public void stop(ComponentId name) {
        // nothing
    }

    // ------------------ Common deployment methods ------------------------
    
    public DeploymentMessage checkPipa(String token, WDSService wds, InputStream input, String name) {
        DeploymentMessage msg = null;
        try {
            PIPATask task = loadPIPADescriptor(input);
            if (task.isValid()) {
                PIPATask existing = wds.getPipaTask(task.getFormURLAsString(), token);
                if (existing != null) {
                    msg = new DeploymentMessage(Level.ERROR, "PIPA task already exists: "+task.getFormURLAsString());
                    msg.setResource(name);
                }
            } else {
                msg = new DeploymentMessage(Level.ERROR, "Invalid PIPA task descriptor: "+name);
                msg.setResource(name);
            }
        } catch (Exception e) {
            LOG.error("Error while storing item: "+name, e);
            msg = new DeploymentMessage(Level.ERROR, e.toString());
            msg.setResource(name);
        }
        return msg;
    }

    public DeploymentMessage processPipa(String token, WDSService wds, InputStream input, String name) {
        DeploymentMessage msg = null;
        try {
            PIPATask task = loadPIPADescriptor(input);
            if (task.isValid()) {
                wds.storePipaTask(task, token);
            } else {
                msg = new DeploymentMessage(Level.ERROR, "Invalid PIPA task descriptor: "+name);
            }
        } catch (Exception e) {
            LOG.error("Error while storing item: "+name, e);
            msg = new DeploymentMessage(Level.ERROR, e.toString());
            msg.setResource(name);
        }
        return msg;
    }

    public PIPATask loadPIPADescriptor(InputStream input) throws IOException {
        Properties prop = new Properties();
        prop.load(input);
        PIPATask task = PIPALoader.parsePipa(prop);
        return task;
    }
    
    public DeploymentMessage checkItem(String token, WDSService wds, InputStream input, String itemURL) {
        DeploymentMessage msg = null;
        try {
            Item existing = wds.retrieveItem(itemURL, token);
            if (existing != null) {
                msg = new DeploymentMessage(Level.ERROR, "PIPA task already exists: "+itemURL);
                msg.setResource(itemURL);
            }
        } catch (Exception e) {
            LOG.error("Error while checking item: "+itemURL, e);
            msg = new DeploymentMessage(Level.ERROR, e.toString());
            msg.setResource(itemURL);
        }
        return msg;
    }

    public DeploymentMessage processItem(String token, WDSService wds, InputStream input, String itemURL) {
        DeploymentMessage msg = null;
        try {
            Item item = new Item(itemURL, "application/xml", copyToByteArray(input));
            wds.storeItem(item, token);
        } catch (Exception e) {
            LOG.error("Error while storing item: "+itemURL, e);
            msg = new DeploymentMessage(Level.ERROR, e.toString());
            msg.setResource(itemURL);
        }
        return msg;
    }

    public DeploymentMessage checkXForm(String token, WDSService wds, InputStream stream, String formURL) {
        return checkItem(token, wds, stream, formURL);
    }

    public DeploymentMessage processXForm(String token, WDSService wds, InputStream stream, String formURL) {
        DeploymentMessage msg = null;
        try {
            Item item = XFormsProcessor.processXForm(formURL, new ByteArrayInputStream(copyToByteArray(stream)));
            wds.storeItem(item, token);
            return null;
        } catch (Exception e) {
            LOG.error("Error while storing XForm: "+formURL, e);
            msg = new DeploymentMessage(Level.ERROR, e.toString());
            msg.setResource(formURL);
        }
        return msg;
    }
    
    // ------------------ Private stuff ------------------------
    
    /**
     * Copy the content of the stream to a byte array
     */
    private static byte[] copyToByteArray(InputStream input) throws IOException {
        byte[] bytes = new byte[32768];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            int bytesRead = input.read(bytes);
            if (bytesRead < 0) break;
            output.write(bytes, 0, bytesRead);
        }
        return output.toByteArray();
    }
    
    private static String relativePath(File parent, File child) {
        return parent.toURI().relativize(child.toURI()).toString();
    }
    
    private static void close(Closeable c) {
        try {
            c.close();
        } catch (Exception e) {
            // ignore
        }
    }
}