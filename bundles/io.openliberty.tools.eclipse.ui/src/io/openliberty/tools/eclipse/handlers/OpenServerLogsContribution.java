/*******************************************************************************
 * Copyright (c) 2022, 2025 IBM Corporation and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial implementation
 *******************************************************************************/
package io.openliberty.tools.eclipse.handlers;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.ISelectionService;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.CompoundContributionItem;
import org.eclipse.ui.menus.IWorkbenchContribution;
import org.eclipse.ui.services.IServiceLocator;

import io.openliberty.tools.eclipse.DevModeOperations;
import io.openliberty.tools.eclipse.logging.Trace;
import io.openliberty.tools.eclipse.messages.Messages;
import io.openliberty.tools.eclipse.utils.Utils;

/**
 * Dynamic contribution that builds the
 * {@code Liberty → Open server logs → [Message|Trace|FFDC] → file} cascade
 * for the Project Explorer right-click context menu.
 *
 * <p>Registered in {@code plugin.xml} as a {@code dynamic} element inside the
 * {@code Liberty} menu contribution on
 * {@code popup:org.eclipse.ui.navigator.ProjectExplorer}.
 *
 * <p>The file list is resolved fresh on every menu open so it always reflects
 * the current state of the server logs directory.
 */
public class OpenServerLogsContribution extends CompoundContributionItem
        implements IWorkbenchContribution {

    public OpenServerLogsContribution() {}

    public OpenServerLogsContribution(String id) {
        super(id);
    }

    @Override
    public void initialize(IServiceLocator serviceLocator) {
        // no-op
    }

    /**
     * Builds the contribution items. Returns a single item: the
     * "Open server logs ▶" sub-menu containing Message / Trace / FFDC sub-menus.
     */
    @Override
    protected IContributionItem[] getContributionItems() {
        IProject iProject = getSelectedProject();
        if (iProject == null) {
            return new IContributionItem[0];
        }

        DevModeOperations devModeOps = DevModeOperations.getInstance();

        MenuManager openLogsMenu = new MenuManager(
                Messages.getMessage("dashboard_action_open_server_logs"));
        openLogsMenu.add(buildLogTypeMenu(devModeOps, iProject,
                Messages.getMessage("dashboard_action_open_message_logs"),
                DevModeOperations.LOG_TYPE_MESSAGES));
        openLogsMenu.add(buildLogTypeMenu(devModeOps, iProject,
                Messages.getMessage("dashboard_action_open_trace_logs"),
                DevModeOperations.LOG_TYPE_TRACE));
        openLogsMenu.add(buildLogTypeMenu(devModeOps, iProject,
                Messages.getMessage("dashboard_action_open_ffdc_logs"),
                DevModeOperations.LOG_TYPE_FFDC));

        return new IContributionItem[] { openLogsMenu };
    }

    /**
     * Builds a single log-type sub-menu (e.g. "Message logs ▶") whose children
     * are the individual log files found on disk. A disabled placeholder is shown
     * when no files exist.
     */
    private MenuManager buildLogTypeMenu(DevModeOperations devModeOps,
            IProject iProject, String label, String logType) {

        MenuManager typeMenu = new MenuManager(label);

        List<Path> logFiles = Collections.emptyList();
        try {
            logFiles = devModeOps.getServerLogFiles(iProject, logType);
        } catch (Exception e) {
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_HANDLERS,
                        "Error listing " + logType + " files for " + iProject.getName(), e);
            }
        }

        if (logFiles.isEmpty()) {
            Action placeholder = new Action(
                    Messages.getMessage("dashboard_toolbar_no_logs_found")) {
                @Override public void run() { /* no-op */ }
            };
            placeholder.setEnabled(false);
            typeMenu.add(new ActionContributionItem(placeholder));
        } else {
            for (Path logFile : logFiles) {
                Action openAction = new Action(logFile.getFileName().toString()) {
                    @Override
                    public void run() {
                        devModeOps.openServerLogFile(logFile);
                    }
                };
                typeMenu.add(new ActionContributionItem(openAction));
            }
        }

        return typeMenu;
    }

    /**
     * Resolves the selected project from the Project Explorer selection,
     * falling back to the generic workbench selection if needed.
     */
    private static IProject getSelectedProject() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) {
            return null;
        }
        ISelectionService selectionService = window.getSelectionService();

        ISelection selection = selectionService
                .getSelection("org.eclipse.ui.navigator.ProjectExplorer");
        if (selection == null || selection.isEmpty()) {
            selection = selectionService.getSelection();
        }

        return Utils.getProjectFromSelection(selection);
    }
}
