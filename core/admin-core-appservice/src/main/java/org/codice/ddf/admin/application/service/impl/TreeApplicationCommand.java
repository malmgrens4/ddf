/**
 * Copyright (c) Codice Foundation
 *
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 *
 **/
package org.codice.ddf.admin.application.service.impl;

import java.io.PrintStream;
import java.util.Set;

import org.apache.felix.gogo.commands.Command;
import org.apache.karaf.shell.console.OsgiCommandSupport;
import org.codice.ddf.admin.application.service.ApplicationNode;
import org.codice.ddf.admin.application.service.ApplicationService;
import org.codice.ddf.admin.application.service.ApplicationStatus;
import org.codice.ddf.admin.application.service.ApplicationStatus.ApplicationState;
import org.osgi.framework.ServiceReference;

/**
 * Utilizes the OSGi Command Shell in Karaf and lists all available
 * applications.
 * 
 */
@Command(scope = "app", name = "tree", description = "Creates a hierarchy tree of all of the applications.")
public class TreeApplicationCommand extends OsgiCommandSupport {

    private static final int STATUS_COLUMN_LENGTH;
    static {
        int size = 0;
        for (ApplicationState curState : ApplicationStatus.ApplicationState.values()) {
            if (curState.name().length() > size) {
                size = curState.name().length();
            }
        }
        STATUS_COLUMN_LENGTH = size;
    }

    @Override
    protected Object doExecute() throws Exception {

        PrintStream console = System.out;

        ServiceReference ref = getBundleContext().getServiceReference(
                ApplicationService.class.getName());

        if (ref == null) {
            console.println("Application Status service is unavailable.");
            return null;
        }
        try {
            ApplicationService appService = (ApplicationService) getBundleContext().getService(ref);
            if (appService == null) {
                console.println("Application Status service is unavailable.");
                return null;
            }

            // node for the application tree
            Set<ApplicationNode> rootApplications = appService.getApplicationTree();
            for (ApplicationNode curRoot : rootApplications) {
                printNode(curRoot, "");
            }
            // console.printf("%s%10s\n", "State", "Name");

        } finally {
            getBundleContext().ungetService(ref);
        }
        return null;
    }

    private void printNode(ApplicationNode appNode, String appender) {
        PrintStream console = System.out;

        console.println(appender + "+- " + appNode.getApplication().getName());
        appender += "|   ";
        for (ApplicationNode curChild : appNode.getChildren()) {
            printNode(curChild, appender);
        }

    }

}
