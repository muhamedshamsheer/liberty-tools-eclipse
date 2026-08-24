/*******************************************************************************
 * Copyright (c) 2022, 2023 IBM Corporation and others.
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
package io.openliberty.tools.eclipse.ui.dashboard;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuCreator;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.commands.ActionHandler;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Table;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.part.ViewPart;

import io.openliberty.tools.eclipse.DevModeOperations;
import io.openliberty.tools.eclipse.Project;
import io.openliberty.tools.eclipse.WorkspaceProjectsModel;
import io.openliberty.tools.eclipse.logging.Trace;
import io.openliberty.tools.eclipse.messages.Messages;
import io.openliberty.tools.eclipse.ui.launch.shortcuts.OpenGradleTestReportAction;
import io.openliberty.tools.eclipse.ui.launch.shortcuts.OpenMavenITestReportAction;
import io.openliberty.tools.eclipse.ui.launch.shortcuts.OpenMavenUTestReportAction;
import io.openliberty.tools.eclipse.ui.launch.shortcuts.RunTestsAction;
import io.openliberty.tools.eclipse.ui.launch.shortcuts.StartAction;
import io.openliberty.tools.eclipse.ui.launch.shortcuts.StartConfigurationDialogAction;
import io.openliberty.tools.eclipse.ui.launch.shortcuts.StartInContainerAction;
import io.openliberty.tools.eclipse.ui.launch.shortcuts.StopAction;
import io.openliberty.tools.eclipse.utils.ErrorHandler;
import io.openliberty.tools.eclipse.utils.Utils;

/**
 * View of Liberty application projects and dev mode actions to be processed on the selected projects.
 */
public class DashboardView extends ViewPart {

    /** Dashboard view ID. */
    public static final String ID = "io.openliberty.tools.eclipse.views.liberty.devmode.dashboard";

    /** Liberty logo path. */
    public static final String LIBERTY_LOGO_PATH = "icons/openLibertyLogo.png";

    /** Server logs icon path. */
    public static final String SERVER_LOGS_IMG_PATH = "icons/serverLogs.png";

    /** Maven image tag path. */
    public static final String MAVEN_IMG_TAG_PATH = "icons/mavenTag.png";

    /** Gradle image tag path. */
    public static final String GRADLE_IMG_TAG_PATH = "icons/gradleTag.png";

    /**
     * Menu Constants.
     */
    public static final String APP_MENU_ACTION_START = Messages.getMessage("dashboard_action_start");
    public static final String APP_MENU_ACTION_START_CONFIG = Messages.getMessage("dashboard_action_start_config");
    public static final String APP_MENU_ACTION_START_IN_CONTAINER = Messages.getMessage("dashboard_action_start_in_container");
    public static final String APP_MENU_ACTION_DEBUG = Messages.getMessage("dashboard_action_debug");
    public static final String APP_MENU_ACTION_DEBUG_CONFIG = Messages.getMessage("dashboard_action_debug_config");
    public static final String APP_MENU_ACTION_DEBUG_IN_CONTAINER = Messages.getMessage("dashboard_action_debug_in_container");
    public static final String APP_MENU_ACTION_STOP = Messages.getMessage("dashboard_action_stop");
    public static final String APP_MENU_ACTION_RUN_TESTS = Messages.getMessage("dashboard_action_run_tests");
    public static final String APP_MENU_ACTION_VIEW_MVN_IT_REPORT = Messages.getMessage("dashboard_action_view_mvn_it_report");
    public static final String APP_MENU_ACTION_VIEW_MVN_UT_REPORT = Messages.getMessage("dashboard_action_view_mvn_ut_report");
    public static final String APP_MENU_ACTION_VIEW_GRADLE_TEST_REPORT = Messages.getMessage("dashboard_action_view_gradle_test_report");
    public static final String APP_MENU_ACTION_OPEN_SERVER_LOGS = Messages.getMessage("dashboard_action_open_server_logs");
    public static final String APP_MENU_ACTION_OPEN_MESSAGE_LOGS = Messages.getMessage("dashboard_action_open_message_logs");
    public static final String APP_MENU_ACTION_OPEN_TRACE_LOGS = Messages.getMessage("dashboard_action_open_trace_logs");
    public static final String APP_MENU_ACTION_OPEN_FFDC_LOGS = Messages.getMessage("dashboard_action_open_ffdc_logs");
    public static final String DASHBORD_TOOLBAR_ACTION_REFRESH = Messages.getMessage("dashboard_toolbar_refresh");

    /**
     * view actions.
     */
    private Action startAction;
    private Action startConfigDialogAction;
    private Action startInContainerAction;
    private Action debugAction;
    private Action debugConfigDialogAction;
    private Action debugInContainerAction;
    private Action stopAction;
    private Action runTestAction;
    private Action viewMavenITestReportsAction;
    private Action viewMavenUTestReportsAction;
    private Action viewGradleTestReportsAction;
    private Action openServerLogsAction;
    private Action refreshAction;

    /**
     * Table viewer that holds the entries in the dashboard.
     */
    TableViewer viewer;

    /**
     * DevModeOperations reference.
     */
    DevModeOperations devModeOps;

    /**
     * Constructor.
     */
    public DashboardView() {
        devModeOps = DevModeOperations.getInstance();
        devModeOps.setDashboardView(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createPartControl(Composite parent) {
        viewer = new TableViewer(parent, SWT.SINGLE | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION);
        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.setLabelProvider(new DashboardEntryLabelProvider(devModeOps));

        devModeOps.refreshDashboardView(true);

        createActions();
        createContextMenu();
        addToolbarActions();
        getSite().setSelectionProvider(viewer);

        // Enable/disable the server logs button based on whether a project is selected.
        viewer.addSelectionChangedListener(new ISelectionChangedListener() {
            @Override
            public void selectionChanged(SelectionChangedEvent event) {
                openServerLogsAction.setEnabled(!event.getSelection().isEmpty());
                getViewSite().getActionBars().updateActionBars();
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setFocus() {
        viewer.getControl().setFocus();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void dispose() {
        super.dispose();
        // null out viewer so we don't try to update upon a resource change listener notification
        viewer = null;
    }

    /**
     * Creates a right-click menu.
     */
    private void createContextMenu() {
        MenuManager menuMgr = new MenuManager();
        menuMgr.setRemoveAllWhenShown(true);
        menuMgr.addMenuListener(new IMenuListener() {
            @Override
            public void menuAboutToShow(IMenuManager mgr) {
                addActionsToContextMenu(mgr);

            }
        });

        Menu menu = menuMgr.createContextMenu(viewer.getControl());
        viewer.getControl().setMenu(menu);

        getSite().registerContextMenu("io.openliberty.tools.eclipse.views.liberty.devmode.dashboard", menuMgr, viewer);
    }

    /**
     * Populates the toolbar.
     */
    private void addToolbarActions() {
        IToolBarManager tbMgr = getViewSite().getActionBars().getToolBarManager();
        tbMgr.add(openServerLogsAction);
        tbMgr.add(refreshAction);
    }

    /**
     * Populates the context menu.
     *
     * @param mgr The menu manager.
     */
    private void addActionsToContextMenu(IMenuManager mgr) {
        IProject iProject = Utils.getActiveProject();
        String projectName = iProject.getName();
        Project project = devModeOps.getProjectModel().getProject(projectName);

        if (project != null) {
            mgr.add(startAction);
            mgr.add(startInContainerAction);
            mgr.add(startConfigDialogAction);
            mgr.add(debugAction);
            mgr.add(debugInContainerAction);
            mgr.add(debugConfigDialogAction);
            mgr.add(stopAction);
            mgr.add(runTestAction);

            if (project.getBuildType() == Project.BuildType.MAVEN) {
                mgr.add(viewMavenITestReportsAction);
                mgr.add(viewMavenUTestReportsAction);
            } else if (project.getBuildType() == Project.BuildType.GRADLE) {
                mgr.add(viewGradleTestReportsAction);
            } else {
                String msg = "Project" + projectName + "is not a Gradle or Maven project.";
                if (Trace.isEnabled()) {
                    Trace.getTracer().trace(Trace.TRACE_UI, msg);
                }
                ErrorHandler.processErrorMessage(Messages.getMessage("project_not_gradle_or_maven", projectName), true);
                return;
            }

        }
    }

    /**
     * Builds the "Open server logs" cascading sub-menu.
     * Structure: Open server logs > Message logs > [files...]
     *                              > Trace logs  > [files...]
     *                              > FFDC logs   > [files...]
     *
     * @param iProject The selected project.
     * @return A populated {@link MenuManager} ready to be added to the parent menu.
     */
    private IMenuManager buildServerLogsSubMenu(IProject iProject) {
        MenuManager logsMenu = new MenuManager(APP_MENU_ACTION_OPEN_SERVER_LOGS);

        logsMenu.add(buildLogTypeSubMenu(iProject, APP_MENU_ACTION_OPEN_MESSAGE_LOGS, DevModeOperations.LOG_TYPE_MESSAGES));
        logsMenu.add(buildLogTypeSubMenu(iProject, APP_MENU_ACTION_OPEN_TRACE_LOGS, DevModeOperations.LOG_TYPE_TRACE));
        logsMenu.add(buildLogTypeSubMenu(iProject, APP_MENU_ACTION_OPEN_FFDC_LOGS, DevModeOperations.LOG_TYPE_FFDC));

        return logsMenu;
    }

    /**
     * Builds a sub-menu for one log type (e.g. "Message logs").
     * Each file found on disk becomes a clickable action that opens the file.
     * If no files exist, a single disabled placeholder is shown.
     */
    private IMenuManager buildLogTypeSubMenu(IProject iProject, String label, String logType) {
        MenuManager typeMenu = new MenuManager(label);

        List<Path> logFiles = devModeOps.getServerLogFiles(iProject, logType);

        if (logFiles.isEmpty()) {
            Action placeholder = new Action(Messages.getMessage("dashboard_toolbar_no_logs_found")) {
                @Override
                public void run() { /* no-op */ }
            };
            placeholder.setEnabled(false);
            typeMenu.add(placeholder);
        } else {
            for (Path logFile : logFiles) {
                String fileName = logFile.getFileName().toString();
                Action openAction = new Action(fileName) {
                    @Override
                    public void run() {
                        devModeOps.openServerLogFile(logFile);
                    }
                };
                typeMenu.add(openAction);
            }
        }

        return typeMenu;
    }

    /**
     * Instantiates menu and toolbar actions.
     */
    private void createActions() {
        ImageDescriptor ActionImg = null;
        ImageDescriptor refreshImg = null;
        ImageDescriptor serverLogsImg = null;

        // Get the image descriptors for the menu actions and toolbar.
        // If there is a failure, display the error and proceed without the icons.
        try {
            ActionImg = ImageDescriptor.createFromURL(new URL("platform:/plugin/org.eclipse.jdt.debug.ui/icons/full/elcl16/thread_view.gif"));
            refreshImg = ImageDescriptor.createFromURL(new URL("platform:/plugin/org.eclipse.ui.browser/icons/clcl16/nav_refresh.png"));
            serverLogsImg = ImageDescriptor.createFromURL(new URL("platform:/plugin/io.openliberty.tools.eclipse.ui/" + SERVER_LOGS_IMG_PATH));
        } catch (Exception e) {
            String msg = "An error was detected while retrieving image descriptions.";
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_UI, msg, e);
            }
            ErrorHandler.processWarningMessage(Messages.getMessage("image_descriptions_error"), e, true);
        }

        // Activate the Liberty tools context.
        IContextService contextService = getSite().getService(IContextService.class);
        contextService.activateContext("io.openliberty.tools.eclipse.context");

        // Menu: Start.
        startAction = new Action(APP_MENU_ACTION_START) {
            @Override
            public void run() {
                IProject iProject = devModeOps.getSelectedDashboardProject();
                try {
                    StartAction.run(iProject, ILaunchManager.RUN_MODE);
                } catch (Exception e) {
                    String msg = "An error was detected during the " + APP_MENU_ACTION_START + " action.";
                    if (Trace.isEnabled()) {
                        Trace.getTracer().trace(Trace.TRACE_UI, msg, e);
                    }
                    ErrorHandler.processErrorMessage(Messages.getMessage("action_general_error", APP_MENU_ACTION_START), e, true);
                }
            }
        };

        startAction.setImageDescriptor(ActionImg);
        startAction.setActionDefinitionId("io.openliberty.tools.eclipse.project.start.command");
        IHandlerService handlerService = getSite().getService(IHandlerService.class);
        ActionHandler startHandler = new ActionHandler(startAction);
        handlerService.activateHandler(startAction.getActionDefinitionId(), startHandler);

        // Menu: Start with parameters.
        startConfigDialogAction = new Action(APP_MENU_ACTION_START_CONFIG) {
            @Override
            public void run() {
                IProject iProject = devModeOps.getSelectedDashboardProject();
                try {
                    StartConfigurationDialogAction.run(iProject, ILaunchManager.RUN_MODE);
                } catch (Exception e) {
                    String msg = "An error was detected during the " + APP_MENU_ACTION_START_CONFIG + " action.";
                    if (Trace.isEnabled()) {
                        Trace.getTracer().trace(Trace.TRACE_UI, msg, e);
                    }
                    ErrorHandler.processErrorMessage(Messages.getMessage("action_general_error", APP_MENU_ACTION_START_CONFIG), e,
                            true);
                }
            }
        };
        startConfigDialogAction.setImageDescriptor(ActionImg);
        startConfigDialogAction.setActionDefinitionId("io.openliberty.tools.eclipse.project.startConfigDialog.command");
        ActionHandler startConfigDialogHandler = new ActionHandler(startConfigDialogAction);
        handlerService.activateHandler(startConfigDialogAction.getActionDefinitionId(), startConfigDialogHandler);

        // Menu: Start in container.
        startInContainerAction = new Action(APP_MENU_ACTION_START_IN_CONTAINER) {
            @Override
            public void run() {
                IProject iProject = devModeOps.getSelectedDashboardProject();
                try {
                    StartInContainerAction.run(iProject, ILaunchManager.RUN_MODE);
                } catch (Exception e) {
                    String msg = "An error was detected during the " + APP_MENU_ACTION_START_IN_CONTAINER
                                 + " action.";
                    if (Trace.isEnabled()) {
                        Trace.getTracer().trace(Trace.TRACE_UI, msg, e);
                    }
                    ErrorHandler.processErrorMessage(Messages.getMessage("action_general_error", APP_MENU_ACTION_START_IN_CONTAINER), e, true);
                }
            }
        };
        startInContainerAction.setImageDescriptor(ActionImg);
        startInContainerAction.setActionDefinitionId("io.openliberty.tools.eclipse.project.startInContainer.command");
        ActionHandler startWithContainerHandler = new ActionHandler(startInContainerAction);
        handlerService.activateHandler(startInContainerAction.getActionDefinitionId(), startWithContainerHandler);

        // Menu: Debug in container.
        debugInContainerAction = new Action(APP_MENU_ACTION_DEBUG_IN_CONTAINER) {
            @Override
            public void run() {
                IProject iProject = devModeOps.getSelectedDashboardProject();
                try {
                    StartInContainerAction.run(iProject, ILaunchManager.DEBUG_MODE);
                } catch (Exception e) {
                    String msg = "An error was detected during the " + APP_MENU_ACTION_DEBUG_IN_CONTAINER
                                 + " action.";
                    if (Trace.isEnabled()) {
                        Trace.getTracer().trace(Trace.TRACE_UI, msg, e);
                    }
                    ErrorHandler.processErrorMessage(Messages.getMessage("action_general_error", APP_MENU_ACTION_DEBUG_IN_CONTAINER), e, true);
                }
            }
        };
        debugInContainerAction.setImageDescriptor(ActionImg);
        debugInContainerAction.setActionDefinitionId("io.openliberty.tools.eclipse.project.debugInContainer.command");
        ActionHandler debugWithContainerHandler = new ActionHandler(debugInContainerAction);
        handlerService.activateHandler(debugInContainerAction.getActionDefinitionId(), debugWithContainerHandler);

        // Menu: Debug.
        debugAction = new Action(APP_MENU_ACTION_DEBUG) {
            @Override
            public void run() {
                IProject iProject = devModeOps.getSelectedDashboardProject();
                try {
                    StartAction.run(iProject, ILaunchManager.DEBUG_MODE);
                } catch (Exception e) {
                    String msg = "An error was detected during the " + APP_MENU_ACTION_DEBUG + " action.";
                    if (Trace.isEnabled()) {
                        Trace.getTracer().trace(Trace.TRACE_UI, msg, e);
                    }
                    ErrorHandler.processErrorMessage(Messages.getMessage("action_general_error", APP_MENU_ACTION_DEBUG), e, true);
                }
            }
        };

        debugAction.setImageDescriptor(ActionImg);
        debugAction.setActionDefinitionId("io.openliberty.tools.eclipse.project.debug.command");
        ActionHandler debugHandler = new ActionHandler(debugAction);
        handlerService.activateHandler(debugAction.getActionDefinitionId(), debugHandler);

        // Menu: Debug with parameters.
        debugConfigDialogAction = new Action(APP_MENU_ACTION_DEBUG_CONFIG) {
            @Override
            public void run() {
                IProject iProject = devModeOps.getSelectedDashboardProject();
                try {
                    StartConfigurationDialogAction.run(iProject, ILaunchManager.DEBUG_MODE);
                } catch (Exception e) {
                    String msg = "An error was detected during the " + APP_MENU_ACTION_DEBUG_CONFIG + " action.";
                    if (Trace.isEnabled()) {
                        Trace.getTracer().trace(Trace.TRACE_UI, msg, e);
                    }
                    ErrorHandler.processErrorMessage(Messages.getMessage("action_general_error", APP_MENU_ACTION_DEBUG_CONFIG), e, true);
                }
            }
        };
        debugConfigDialogAction.setImageDescriptor(ActionImg);
        debugConfigDialogAction.setActionDefinitionId("io.openliberty.tools.eclipse.project.debugConfigDialog.command");
        ActionHandler debugConfigDialogHandler = new ActionHandler(debugConfigDialogAction);
        handlerService.activateHandler(debugConfigDialogAction.getActionDefinitionId(), debugConfigDialogHandler);

        // Menu: Stop.
        stopAction = new Action(APP_MENU_ACTION_STOP) {
            @Override
            public void run() {
                IProject iProject = devModeOps.getSelectedDashboardProject();
                try {
                    StopAction.run(iProject);
                } catch (Exception e) {
                    String msg = "An error was detected during the " + APP_MENU_ACTION_STOP + " action.";
                    if (Trace.isEnabled()) {
                        Trace.getTracer().trace(Trace.TRACE_UI, msg, e);
                    }
                    ErrorHandler.processErrorMessage(Messages.getMessage("action_general_error", APP_MENU_ACTION_STOP), e, true);
                }
            }
        };
        stopAction.setImageDescriptor(ActionImg);
        stopAction.setActionDefinitionId("io.openliberty.tools.eclipse.project.stop.command");
        ActionHandler stopHandler = new ActionHandler(stopAction);
        handlerService.activateHandler(stopAction.getActionDefinitionId(), stopHandler);

        // Menu: Run tests.
        runTestAction = new Action(APP_MENU_ACTION_RUN_TESTS) {
            @Override
            public void run() {
                IProject iProject = devModeOps.getSelectedDashboardProject();
                try {
                    RunTestsAction.run(iProject);
                } catch (Exception e) {
                    String msg = "An error was detected during the " + APP_MENU_ACTION_RUN_TESTS + " action.";
                    if (Trace.isEnabled()) {
                        Trace.getTracer().trace(Trace.TRACE_UI, msg, e);
                    }
                    ErrorHandler.processErrorMessage(Messages.getMessage("action_general_error", APP_MENU_ACTION_RUN_TESTS), e, true);
                }
            }
        };
        runTestAction.setImageDescriptor(ActionImg);
        runTestAction.setActionDefinitionId("io.openliberty.tools.eclipse.project.runTests.command");
        ActionHandler runTestsHandler = new ActionHandler(runTestAction);
        handlerService.activateHandler(runTestAction.getActionDefinitionId(), runTestsHandler);

        // Menu: View integration test report. Maven project specific.
        viewMavenITestReportsAction = new Action(APP_MENU_ACTION_VIEW_MVN_IT_REPORT) {
            @Override
            public void run() {
                IProject iProject = devModeOps.getSelectedDashboardProject();
                try {
                    OpenMavenITestReportAction.run(iProject);
                } catch (Exception e) {
                    String msg = "An error was detected during the " + APP_MENU_ACTION_VIEW_MVN_IT_REPORT
                                 + " action.";
                    if (Trace.isEnabled()) {
                        Trace.getTracer().trace(Trace.TRACE_UI, msg, e);
                    }
                    ErrorHandler.processErrorMessage(Messages.getMessage("action_general_error", APP_MENU_ACTION_VIEW_MVN_IT_REPORT), e, true);
                }
            }
        };
        viewMavenITestReportsAction.setImageDescriptor(ActionImg);
        viewMavenITestReportsAction.setActionDefinitionId("io.openliberty.tools.eclipse.project.viewMvnIntegrationTestReport.command");
        ActionHandler mvnITTestReportHandler = new ActionHandler(viewMavenITestReportsAction);
        handlerService.activateHandler(viewMavenITestReportsAction.getActionDefinitionId(), mvnITTestReportHandler);

        // Menu: View unit test report. Maven project specific.
        viewMavenUTestReportsAction = new Action(APP_MENU_ACTION_VIEW_MVN_UT_REPORT) {
            @Override
            public void run() {
                IProject iProject = devModeOps.getSelectedDashboardProject();
                try {
                    OpenMavenUTestReportAction.run(iProject);
                } catch (Exception e) {
                    String msg = "An error was detected during the " + APP_MENU_ACTION_VIEW_MVN_UT_REPORT
                                 + " action.";
                    if (Trace.isEnabled()) {
                        Trace.getTracer().trace(Trace.TRACE_UI, msg, e);
                    }
                    ErrorHandler.processErrorMessage(Messages.getMessage("action_general_error", APP_MENU_ACTION_VIEW_MVN_UT_REPORT), e, true);
                }
            }
        };
        viewMavenUTestReportsAction.setImageDescriptor(ActionImg);
        viewMavenUTestReportsAction.setActionDefinitionId("io.openliberty.tools.eclipse.project.viewMvnUnitTestReport.command");
        ActionHandler mvnUTTestReportsHandler = new ActionHandler(viewMavenUTestReportsAction);
        handlerService.activateHandler(viewMavenUTestReportsAction.getActionDefinitionId(), mvnUTTestReportsHandler);

        // Menu: View test report. Gradle project specific.
        viewGradleTestReportsAction = new Action(APP_MENU_ACTION_VIEW_GRADLE_TEST_REPORT) {
            @Override
            public void run() {
                IProject iProject = devModeOps.getSelectedDashboardProject();
                try {
                    OpenGradleTestReportAction.run(iProject);
                } catch (Exception e) {
                    String msg = "An error was detected during the " + APP_MENU_ACTION_VIEW_GRADLE_TEST_REPORT
                                 + " action.";
                    if (Trace.isEnabled()) {
                        Trace.getTracer().trace(Trace.TRACE_UI, msg, e);
                    }
                    ErrorHandler.processErrorMessage(Messages.getMessage("action_general_error", APP_MENU_ACTION_VIEW_GRADLE_TEST_REPORT), e,
                            true);
                }
            }
        };
        viewGradleTestReportsAction.setImageDescriptor(ActionImg);
        viewGradleTestReportsAction.setActionDefinitionId("io.openliberty.tools.eclipse.project.viewGradleTestReport.command");
        ActionHandler gradleTestReportsHandler = new ActionHandler(viewGradleTestReportsAction);
        handlerService.activateHandler(viewGradleTestReportsAction.getActionDefinitionId(), gradleTestReportsHandler);

        openServerLogsAction = new Action(APP_MENU_ACTION_OPEN_SERVER_LOGS, Action.AS_DROP_DOWN_MENU) {
            @Override
            public void run() {
                // icon click — delegate to the IMenuCreator same as arrow click
                Menu menu = getMenuCreator().getMenu(viewer.getControl());
                if (menu != null && !menu.isDisposed()) {
                    menu.setVisible(true);
                }
            }
        };
        openServerLogsAction.setImageDescriptor(serverLogsImg);
        openServerLogsAction.setEnabled(false);
        openServerLogsAction.setMenuCreator(new IMenuCreator() {
            private Menu menu;
            @Override
            public Menu getMenu(Control parent) {
                dispose();
                IProject iProject = devModeOps.getSelectedDashboardProject();
                if (iProject == null) return null;
                // Root manager materialises as the popup. The "Open server logs"
                // sub-menu is added as a child so it appears as a labelled entry
                // with Message / Trace / FFDC nested under it.
                MenuManager root = new MenuManager();
                root.add(buildServerLogsSubMenu(iProject));
                menu = root.createContextMenu(parent);
                root.update(true);
                return menu;
            }
            @Override
            public Menu getMenu(Menu parent) { return getMenu(viewer.getControl()); }
            @Override
            public void dispose() {
                if (menu != null && !menu.isDisposed()) menu.dispose();
                menu = null;
            }
        });

        // Toolbar: Refresh the project list.
        refreshAction = new Action(DASHBORD_TOOLBAR_ACTION_REFRESH) {
            @Override
            public void run() {
                devModeOps.refreshDashboardView(true);
            }
        };
        refreshAction.setImageDescriptor(refreshImg);
    }

    public void setInput(List<String> sortedDashboardProjectList) {
        if (viewer != null) {
            viewer.setInput(sortedDashboardProjectList);
        }
    }

    /**
     * Refreshes the dashboard view.
     */
    public void refreshDashboardView(WorkspaceProjectsModel projectModel, boolean reportError) {
        try {
            projectModel.createNewCompleteWorkspaceModelWithClassify();
            setInput(projectModel.getSortedDashboardProjectList());
        } catch (Exception e) {
            String msg = "An error was detected when the Liberty dashboard content was refreshed.";
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_UI, msg, e);
            }
            ErrorHandler.processErrorMessage(Messages.getMessage("dashboard_refresh_error"), e, reportError);
            return;
        }
    }

    public Table getTable() {
        return viewer.getTable();
    }
}
