package org.zowe.explorerjava;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;
import zowe.client.sdk.rest.exception.ZosmfRequestException;
import zowe.client.sdk.zosfiles.dsn.model.Dataset;
import zowe.client.sdk.zosfiles.dsn.model.Member;
import zowe.client.sdk.zosfiles.uss.model.UnixFile;
import zowe.client.sdk.zosjobs.model.Job;
import zowe.client.sdk.zosjobs.model.JobFile;
import zowe.client.sdk.zosmfinfo.model.DefinedSystem;
import zowe.client.sdk.zosvariables.response.VariableResponse;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Main z/OS Workbench for Zowe tool window.
 * <p>
 * UI code talks only to Explorer service facades. z/OSMF access is delegated to the
 * Zowe Client Java SDK through JobService, DataSetService, and UssService.
 */
public final class ZoweToolWindowFactory implements ToolWindowFactory {

    private final JBLabel globalState =
            new JBLabel("Configure a z/OSMF connection to begin.");

    // Jobs
    private final DefaultMutableTreeNode jobsRoot =
            new DefaultMutableTreeNode("Jobs");
    private final DefaultTreeModel jobsModel =
            new DefaultTreeModel(jobsRoot);
    private final JTree jobsTree =
            new JTree(jobsModel);
    private final JBTextArea jobDetails =
            textArea(false);
    private final JBScrollPane jobPreviewScroll =
            new JBScrollPane(jobDetails);
    private final JBSplitter jobsSplitter =
            new JBSplitter(false, 0.35f);
    private final JButton jobPreviewToggle =
            new JButton();
    private boolean jobPreviewExpanded;

    /*
     * Job search filters.
     *
     * Owner defaults to the active z/OSMF connection user.
     * Prefix defaults to "*" so that all jobs for that owner are displayed.
     */
    private final JBTextField jobOwner = new JBTextField();
    private final JBTextField jobPrefix = new JBTextField("*");
    /**
     * Job monitoring cadence.
     * <p>
     * This mirrors the general approach used by the Kotlin z/OS Workbench for Zowe:
     * perform periodic status requests instead of keeping one blocking
     * polling operation alive.
     */
    private static final long JOB_MONITOR_INTERVAL_SECONDS = 5L;

    /**
     * Start/Stop button for the selected job monitor.
     */
    private final JButton jobMonitorButton =
            new JButton("Start Monitor");

    /**
     * Only one monitor thread exists for this explorer instance.
     * <p>
     * scheduleWithFixedDelay ensures requests never overlap. A new status
     * request is not started until the previous request has completed and
     * the five-second delay has elapsed.
     */
    private final ScheduledExecutorService jobMonitorExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {

                Thread thread =
                        new Thread(
                                r,
                                "zowe-job-monitor");

                thread.setDaemon(true);

                return thread;
            });

    /**
     * Used to invalidate an old polling operation immediately when the
     * monitor is stopped or the z/OS connection changes.
     */
    private final AtomicLong jobMonitorGeneration =
            new AtomicLong();

    private volatile ScheduledFuture<?> jobMonitorFuture;

    private volatile Job monitoredJob;

    // Data sets
    private final DefaultMutableTreeNode dsnRoot =
            new DefaultMutableTreeNode("Data Sets");
    private final DefaultTreeModel dsnModel =
            new DefaultTreeModel(dsnRoot);
    private final JTree dsnTree =
            new JTree(dsnModel);
    private final JBTextArea dsnDetails =
            textArea(false);
    private final JBScrollPane dsnPreviewScroll =
            new JBScrollPane(dsnDetails);
    private final JBSplitter dsnSplitter =
            new JBSplitter(false, 0.35f);
    private final JButton dsnPreviewToggle =
            new JButton();
    private boolean dsnPreviewExpanded;
    private final ComboBox<String> dsnMask =
            new ComboBox<>();

    // Connections
    private final ComboBox<ConnectionProfile> connectionCombo =
            new ComboBox<>();
    private boolean isUpdatingConnectionCombo = false;

    // USS
    private final DefaultMutableTreeNode ussRoot =
            new DefaultMutableTreeNode("USS");
    private final DefaultTreeModel ussModel =
            new DefaultTreeModel(ussRoot);
    private final JTree ussTree =
            new JTree(ussModel);
    private final JBTextArea ussDetails =
            textArea(false);
    private final PathComboBox ussPath =
            new PathComboBox("/");
    private final JBTextField ussFilter =
            new JBTextField();
    private List<UnixFile> currentUssItems =
            List.of();
    private String currentUssPath =
            "/";

    // Commands
    private final JBTextArea tsoOutput =
            textArea(false);
    private final JBTextField tsoInput =
            new JBTextField();
    private final JBLabel tsoSessionState =
            new JBLabel("TSO session: stopped");

    private final JBTextArea consoleOutput =
            textArea(false);
    private final JBTextField consoleInput =
            new JBTextField();

    private final JBTextArea sshOutput =
            textArea(false);
    private final JBTextField sshInput =
            new JBTextField();

    // Symbols & Variables
    private final DefaultTableModel symbolsTableModel = new DefaultTableModel(
            new Object[]{"Name", "Value", "Description"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable symbolsTable = new JTable(symbolsTableModel);
    private final JComboBox<String> symbolSystemCombo = new ComboBox<>();
    private final JComboBox<String> symbolTypeCombo = new ComboBox<>(new String[]{"System Symbols", "System Variables"});
    private final JBTextField symbolFilterField = new JBTextField();
    private final JBLabel symbolsStatusLabel = new JBLabel("Select scope/type and click Refresh.");
    private final JButton symbolAddButton = new JButton("Add...", AllIcons.General.Add);
    private final JButton symbolEditButton = new JButton("Edit...", AllIcons.Actions.Edit);
    private final JButton symbolDeleteButton = new JButton("Delete", AllIcons.Actions.GC);
    private final JButton symbolRenameButton = new JButton("Rename...", AllIcons.Actions.Replace);
    private List<DefinedSystem> definedSystemsList = new ArrayList<>();
    private List<VariableResponse> rawSymbolsList = new ArrayList<>();
    private boolean isUpdatingSymbolCombo = false;

    private RemoteEditorManager remoteEditors;
    private volatile CommandService commandService;

    @Override
    public void createToolWindowContent(
            @NotNull Project project,
            @NotNull ToolWindow toolWindow) {

        remoteEditors = new RemoteEditorManager(project);

        Disposer.register(project, remoteEditors);

        Disposer.register(project, () -> {

            stopJobMonitor(false);

            jobMonitorExecutor.shutdownNow();

            CommandService service =
                    commandService;

            if (service != null) {
                service.close();
            }
        });

        project.getMessageBus()
                .connect(project)
                .subscribe(
                        RemoteSaveListener.TOPIC,
                        (RemoteSaveListener) target ->
                                setState("Saved " + target + " to z/OS.")
                );

        /*
         * Initialize the Jobs owner filter from the currently configured
         * z/OSMF connection.
         */
        resetJobFilters();

        JPanel panel =
                new JPanel(new BorderLayout(6, 6));

        panel.add(
                createGlobalToolbar(project),
                BorderLayout.NORTH);

        JTabbedPane tabs =
                new JTabbedPane();

        tabs.addTab(
                "Jobs",
                createJobsPanel(project));

        tabs.addTab(
                "Data Sets",
                createDataSetsPanel(project));

        tabs.addTab(
                "USS",
                createUssPanel(project));

        tabs.addTab(
                "Commands",
                createCommandsPanel(project));

        tabs.addTab(
                "Symbols",
                createSymbolsPanel(project));

        refreshSystemsList(project);

        panel.add(
                tabs,
                BorderLayout.CENTER);

        panel.add(
                globalState,
                BorderLayout.SOUTH);

        Content content =
                ContentFactory.getInstance()
                        .createContent(
                                panel,
                                "Explorer",
                                false);

        toolWindow.getContentManager()
                .addContent(content);
    }

    // -------------------------------------------------------------------------
    // Global connection toolbar
    // -------------------------------------------------------------------------

    private JComponent createGlobalToolbar(Project project) {

        JPanel toolbar =
                new JPanel(
                        new WrapLayout(
                                FlowLayout.LEFT,
                                4,
                                2));

        connectionCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof ConnectionProfile p) {
                    setText(p.getDisplayName());
                }
                return c;
            }
        });
        connectionCombo.setToolTipText("Select active z/OS connection");

        updateConnectionCombo();

        connectionCombo.addActionListener(e -> {
            if (isUpdatingConnectionCombo) {
                return;
            }
            ConnectionProfile selected = (ConnectionProfile) connectionCombo.getSelectedItem();
            if (selected != null) {
                ZoweConnectionSettings settings = ZoweConnectionSettings.getInstance();
                if (!selected.id.equals(settings.getActiveProfile().id)) {
                    settings.setActiveProfileId(selected.id);
                    onConnectionSwitched(project, "Switched active connection to " + selected.getDisplayName());
                }
            }
        });

        JButton manage = new JButton("Manage...", AllIcons.General.Settings);
        manage.setToolTipText("Manage connection profiles");

        manage.addActionListener(e -> {
            if (new ConnectionDialog().showAndGet()) {
                updateConnectionCombo();
                ConnectionProfile active = ZoweConnectionSettings.getInstance().getActiveProfile();
                onConnectionSwitched(project, "Connection saved for " + active.getDisplayName());
            }
        });

        toolbar.add(new JBLabel("Connection:"));
        toolbar.add(connectionCombo);
        toolbar.add(manage);

        return toolbar;
    }

    private void updateConnectionCombo() {
        isUpdatingConnectionCombo = true;
        try {
            ZoweConnectionSettings settings = ZoweConnectionSettings.getInstance();
            List<ConnectionProfile> profiles = settings.getProfiles();
            DefaultComboBoxModel<ConnectionProfile> model = new DefaultComboBoxModel<>();
            for (ConnectionProfile p : profiles) {
                model.addElement(p);
            }
            connectionCombo.setModel(model);
            ConnectionProfile active = settings.getActiveProfile();
            if (active != null) {
                connectionCombo.setSelectedItem(active);
            }
        } finally {
            isUpdatingConnectionCombo = false;
        }
    }

    private void onConnectionSwitched(Project project, String statusMessage) {
        CommandService old = commandService;
        commandService = null;

        if (old != null) {
            ApplicationManager
                    .getApplication()
                    .executeOnPooledThread(old::close);
        }

        tsoSessionState.setText("TSO session: stopped");

        /*
         * A monitor belongs to the connection under which it was
         * started. Stop it before switching to the new connection.
         */
        stopJobMonitor(false);

        /*
         * A connection change also resets the Jobs filters.
         * The owner is always initialized from the currently
         * configured connection user.
         */
        resetJobFilters();

        setState(statusMessage);

        /*
         * Refresh the Jobs view immediately using the new
         * connection/user.
         */
        refreshJobs(project);

        /*
         * Refresh system list for Symbols tab.
         */
        refreshSystemsList(project);
    }

    // -------------------------------------------------------------------------
    // Jobs
    // -------------------------------------------------------------------------

    private JComponent createJobsPanel(Project project) {

        JPanel panel =
                new JPanel(
                        new BorderLayout(
                                4,
                                4));

        /*
         * Use two toolbar rows.
         *
         * Row 1 = search/filter controls
         * Row 2 = job operations
         *
         * This prevents the Jobs toolbar from becoming excessively wide.
         */
        JPanel north =
                new JPanel();

        north.setLayout(
                new BoxLayout(
                        north,
                        BoxLayout.Y_AXIS));

        JPanel filterToolbar =
                new JPanel(
                        new WrapLayout(
                                FlowLayout.LEFT,
                                4,
                                2));

        JPanel actionToolbar =
                new JPanel(
                        new WrapLayout(
                                FlowLayout.LEFT,
                                4,
                                2));

        jobOwner.setColumns(10);
        jobPrefix.setColumns(10);

        jobOwner.setToolTipText(
                "Job owner. Defaults to the active connection user. "
                        + "Use * to search all owners you are authorized to view.");

        jobPrefix.setToolTipText(
                "Job name/prefix, for example * or MYJOB*.");

        JButton refresh =
                new JButton("Refresh");

        JButton submit =
                new JButton("Submit JCL DSN");

        JButton jcl =
                new JButton("Show JCL");

        JButton spool =
                new JButton("Load Spool Files");

        JButton spoolContent =
                new JButton("Open Spool");

        JButton openSpoolEditor =
                new JButton("Open in Editor");

        JButton saveSpool =
                new JButton("Save Spool As...");

        refresh.addActionListener(
                e -> refreshJobs(project));

        /*
         * Pressing Enter in either filter performs a refresh.
         */
        jobOwner.addActionListener(
                e -> refreshJobs(project));

        jobPrefix.addActionListener(
                e -> refreshJobs(project));

        submit.addActionListener(
                e -> submit(project));

        jobMonitorButton.addActionListener(e -> {

            if (isJobMonitorRunning()) {

                stopJobMonitor(true);

            } else {

                startJobMonitor(project);
            }
        });

        jcl.addActionListener(
                e -> loadJcl(project));

        spool.addActionListener(
                e -> loadSpoolFiles(project));

        spoolContent.addActionListener(
                e -> loadSelectedSpool(project));

        openSpoolEditor.addActionListener(
                e -> openSpoolInEditor(project));

        saveSpool.addActionListener(
                e -> saveSelectedSpool(project));

        filterToolbar.add(
                new JBLabel("Owner:"));

        filterToolbar.add(jobOwner);

        filterToolbar.add(
                new JBLabel("Job:"));

        filterToolbar.add(jobPrefix);

        filterToolbar.add(refresh);

        actionToolbar.add(submit);
        actionToolbar.add(jobMonitorButton);
        actionToolbar.add(jcl);
        actionToolbar.add(spool);
        actionToolbar.add(spoolContent);
        actionToolbar.add(openSpoolEditor);
        actionToolbar.add(saveSpool);

        north.add(filterToolbar);
        north.add(actionToolbar);

        jobsTree.setRootVisible(true);
        ToolTipManager.sharedInstance().registerComponent(jobsTree);
        jobsTree.setCellRenderer(
                new DefaultTreeCellRenderer() {
                    @Override
                    public Component getTreeCellRendererComponent(
                            JTree tree,
                            Object value,
                            boolean sel,
                            boolean expanded,
                            boolean leaf,
                            int row,
                            boolean hasFocus) {

                        Component c = super.getTreeCellRendererComponent(
                                tree, value, sel, expanded, leaf, row, hasFocus);

                        if (value instanceof DefaultMutableTreeNode node) {
                            Object userObject = node.getUserObject();
                            if (userObject instanceof JobNode) {
                                setIcon(AllIcons.Nodes.Folder);
                            } else if (userObject instanceof SpoolNode) {
                                setIcon(AllIcons.FileTypes.Text);
                            }
                        }

                        if (c instanceof JComponent jc) {
                            jc.setToolTipText(getText());
                        }

                        return c;
                    }
                });

        jobsTree.addTreeSelectionListener(
                e -> showSelectedJobNode());

        jobsTree.addMouseListener(
                new MouseAdapter() {
                    @Override
                    public void mousePressed(
                            MouseEvent e) {

                        maybeShowJobPopup(
                                project,
                                e);
                    }

                    @Override
                    public void mouseReleased(
                            MouseEvent e) {

                        maybeShowJobPopup(
                                project,
                                e);
                    }

                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                            int row = jobsTree.getRowForLocation(e.getX(), e.getY());
                            if (row != -1) {
                                jobsTree.setSelectionRow(row);
                                DefaultMutableTreeNode node = selectedNode(jobsTree);
                                if (node != null) {
                                    Object userObject = node.getUserObject();
                                    if (userObject instanceof JobNode) {
                                        loadSpoolFiles(project);
                                    } else if (userObject instanceof SpoolNode) {
                                        loadSelectedSpool(project);
                                    }
                                }
                            }
                        }
                    }
                });

        jobsSplitter.setFirstComponent(
                new JBScrollPane(jobsTree));

        /*
         * The preview pane is collapsed by default. A thin toggle strip on the
         * right edge expands/collapses it. Opening a spool expands it.
         */
        jobPreviewToggle.setMargin(new Insets(2, 2, 2, 2));
        jobPreviewToggle.setFocusable(false);
        jobPreviewToggle.addActionListener(
                e -> setJobPreviewExpanded(!jobPreviewExpanded));

        JPanel previewStrip =
                new JPanel(new BorderLayout());
        previewStrip.add(jobPreviewToggle, BorderLayout.NORTH);

        setJobPreviewExpanded(false);

        JPanel center =
                new JPanel(new BorderLayout());
        center.add(jobsSplitter, BorderLayout.CENTER);
        center.add(previewStrip, BorderLayout.EAST);

        panel.add(
                north,
                BorderLayout.NORTH);

        panel.add(
                center,
                BorderLayout.CENTER);

        return panel;
    }

    /** Flat, borderless icon button used to expand/close a preview pane. */
    private static void styleToggle(JButton button, boolean expanded) {

        button.setIcon(AllIcons.Actions.PreviewDetails);
        button.setText(null);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        // The IDE button UI enforces a minimum width; pin the size to the icon.
        Dimension size = new Dimension(
                AllIcons.Actions.PreviewDetails.getIconWidth() + 4,
                AllIcons.Actions.PreviewDetails.getIconHeight() + 4);
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        button.setMaximumSize(size);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setToolTipText(
                expanded ? "Close preview" : "Expand preview");
    }

    private void setJobPreviewExpanded(boolean expanded) {

        jobPreviewExpanded = expanded;

        jobsSplitter.setSecondComponent(
                expanded ? jobPreviewScroll : null);

        styleToggle(jobPreviewToggle, expanded);

        jobsSplitter.revalidate();
        jobsSplitter.repaint();
    }

    /**
     * Reset Jobs filters to defaults associated with the current connection.
     * <p>
     * Owner defaults to the current z/OS user.
     * Job prefix defaults to "*".
     */
    private void resetJobFilters() {

        String user =
                ZoweConnectionSettings
                        .getInstance()
                        .getUser();

        if (user == null) {
            user = "";
        }

        jobOwner.setText(
                user.trim()
                        .toUpperCase(Locale.ROOT));

        jobPrefix.setText("*");
    }

    /**
     * Refresh Jobs using the current Owner and Job prefix filters.
     */
    private void refreshJobs(Project project) {

        String owner =
                jobOwner.getText() == null
                        ? ""
                        : jobOwner.getText().trim();

        String prefix =
                jobPrefix.getText() == null
                        ? ""
                        : jobPrefix.getText().trim();

        /*
         * Blank fields are interpreted as wildcard searches.
         */
        if (owner.isEmpty()) {
            owner = "*";
        }

        if (prefix.isEmpty()) {
            prefix = "*";
        }

        owner =
                owner.toUpperCase(Locale.ROOT);

        prefix =
                prefix.toUpperCase(Locale.ROOT);

        /*
         * Reflect normalized values back into the UI.
         */
        jobOwner.setText(owner);
        jobPrefix.setText(prefix);

        final String requestedOwner =
                owner;

        final String requestedPrefix =
                prefix;

        setState(
                "Loading jobs for owner "
                        + requestedOwner
                        + ", job "
                        + requestedPrefix
                        + "...");

        runBackground(project, () -> {

            List<Job> jobs =
                    new JobService(
                            ZoweConnectionProvider.current())
                            .list(
                                    requestedOwner,
                                    requestedPrefix);

            SwingUtilities.invokeLater(() -> {

                jobsRoot.removeAllChildren();

                for (Job job : jobs) {

                    jobsRoot.add(
                            new DefaultMutableTreeNode(
                                    new JobNode(job)));
                }

                jobsModel.reload();

                jobsTree.expandRow(0);

                setState(
                        "Loaded "
                                + jobs.size()
                                + " jobs for owner "
                                + requestedOwner
                                + ".");
            });
        });
    }

    /**
     * Manual Jobs-tab submit dialog.
     */
    private void submit(Project project) {

        String dsn =
                Messages.showInputDialog(
                        project,
                        "Data set containing JCL "
                                + "(for example USER.JCL(MYJOB))",
                        "Submit z/OS Job",
                        null);

        if (dsn == null
                || dsn.isBlank()) {

            return;
        }

        submitDataSetAsJob(
                project,
                dsn.trim());
    }

    /**
     * Common JCL submission method used by both:
     * <p>
     * 1. Jobs -> Submit JCL DSN
     * 2. Data Sets -> member right-click -> Submit as Job
     */
    private void submitDataSetAsJob(
            Project project,
            String dataSet) {

        setState(
                "Submitting "
                        + dataSet
                        + "...");

        runBackground(project, () -> {

            Job submitted =
                    new JobService(
                            ZoweConnectionProvider.current())
                            .submitDataSet(dataSet);

            SwingUtilities.invokeLater(() -> {

                /*
                 * Display the submitted job immediately in the details panel.
                 */
                jobDetails.setText(
                        formatJob(submitted));

                jobDetails.setCaretPosition(0);

                setState(
                        "Submitted "
                                + dataSet
                                + " as "
                                + submitted.getJobName()
                                + " / "
                                + submitted.getJobId());

                /*
                 * Refresh using the current owner/job filters.
                 *
                 * Normally the submitted job will appear immediately because
                 * Owner defaults to the logged-in user.
                 */
                refreshJobs(project);
            });
        });
    }

    /**
     * Starts background monitoring for the currently selected job.
     * <p>
     * Only one job can be monitored at a time.
     * <p>
     * A single status request is made every five seconds until:
     * <p>
     * 1. The job reaches OUTPUT.
     * 2. The user presses Stop Monitor.
     * 3. The connection changes.
     * 4. The project/plugin is disposed.
     * 5. An error occurs.
     */
    private void startJobMonitor(
            final Project project) {

        final Job job =
                selectedJob(project);

        if (job == null) {
            return;
        }

        /*
         * Defensive protection. Normally the button prevents this,
         * but never allow two monitor schedules to coexist.
         */
        stopJobMonitor(false);

        final long generation =
                jobMonitorGeneration.incrementAndGet();

        monitoredJob =
                job;

        /*
         * Capture the current connection/service.
         *
         * If the connection changes, stopJobMonitor() invalidates
         * this monitor before the new connection becomes active.
         */
        final JobService jobService =
                new JobService(
                        ZoweConnectionProvider.current());

        jobMonitorButton.setText(
                "Stop Monitor");

        setState(
                "Monitoring "
                        + job.getJobName()
                        + " / "
                        + job.getJobId()
                        + " every "
                        + JOB_MONITOR_INTERVAL_SECONDS
                        + " seconds...");

        jobMonitorFuture =
                jobMonitorExecutor.scheduleWithFixedDelay(
                        () -> pollJobStatus(
                                project,
                                jobService,
                                job,
                                generation),
                        JOB_MONITOR_INTERVAL_SECONDS,
                        JOB_MONITOR_INTERVAL_SECONDS,
                        TimeUnit.SECONDS);
    }

    /**
     * Performs one status request for the monitored job.
     * <p>
     * scheduleWithFixedDelay uses a single monitor thread, therefore
     * requests cannot overlap even if z/OSMF takes longer than expected.
     */
    private void pollJobStatus(
            final Project project,
            final JobService jobService,
            final Job job,
            final long generation) {

        /*
         * Monitor was stopped/replaced before this poll started.
         */
        if (generation
                != jobMonitorGeneration.get()) {

            return;
        }

        try {

            final Job current =
                    jobService.getStatus(
                            job.getJobName(),
                            job.getJobId());

            /*
             * The monitor may have been stopped while the HTTP request
             * was executing.
             */
            if (generation
                    != jobMonitorGeneration.get()) {

                return;
            }

            SwingUtilities.invokeLater(() -> {

                /*
                 * Avoid stale status updates if Stop Monitor was clicked
                 * between the HTTP response and EDT processing.
                 */
                if (generation
                        != jobMonitorGeneration.get()) {

                    return;
                }

                updateJobNode(current);

                jobDetails.setText(
                        formatJob(current));

                jobDetails.setCaretPosition(0);

                final String status =
                        current.getStatus();

                if ("OUTPUT".equalsIgnoreCase(status)) {

                    finishJobMonitor(
                            current,
                            generation);

                } else {

                    setState(
                            "Monitoring "
                                    + current.getJobName()
                                    + " / "
                                    + current.getJobId()
                                    + " - status "
                                    + status
                                    + " ("
                                    + JOB_MONITOR_INTERVAL_SECONDS
                                    + " second polling)");
                }
            });

        } catch (Throwable ex) {

            SwingUtilities.invokeLater(() -> {

                if (generation
                        != jobMonitorGeneration.get()) {

                    return;
                }

                stopJobMonitor(false);

                setState(
                        "Job monitor stopped due to error: "
                                + ex.getMessage());

                Messages.showErrorDialog(
                        project,
                        ex.toString(),
                        "z/OS Workbench for Zowe");
            });
        }
    }

    /**
     * Called when the monitored job reaches OUTPUT.
     */
    private void finishJobMonitor(
            final Job job,
            final long generation) {

        if (generation
                != jobMonitorGeneration.get()) {

            return;
        }

        ScheduledFuture<?> future =
                jobMonitorFuture;

        if (future != null) {

            future.cancel(false);
        }

        jobMonitorFuture =
                null;

        monitoredJob =
                null;

        /*
         * Invalidate any queued result from this monitor.
         */
        jobMonitorGeneration.incrementAndGet();

        jobMonitorButton.setText(
                "Start Monitor");

        setState(
                "Job "
                        + job.getJobName()
                        + " / "
                        + job.getJobId()
                        + " reached OUTPUT.");
    }

    /**
     * Stops the currently running monitor.
     *
     * @param showState true when explicitly stopped by the user;
     *                  false for internal cleanup/connection changes
     */
    private void stopJobMonitor(
            final boolean showState) {

        /*
         * Increment first so any outstanding HTTP response can no
         * longer modify the UI.
         */
        jobMonitorGeneration.incrementAndGet();

        ScheduledFuture<?> future =
                jobMonitorFuture;

        if (future != null) {

            future.cancel(false);
        }

        Job stoppedJob =
                monitoredJob;

        jobMonitorFuture =
                null;

        monitoredJob =
                null;

        jobMonitorButton.setText(
                "Start Monitor");

        if (showState) {

            if (stoppedJob != null) {

                setState(
                        "Stopped monitoring "
                                + stoppedJob.getJobName()
                                + " / "
                                + stoppedJob.getJobId()
                                + ".");

            } else {

                setState(
                        "Job monitor stopped.");
            }
        }
    }

    /**
     * Indicates whether a monitor schedule currently exists.
     */
    private boolean isJobMonitorRunning() {

        ScheduledFuture<?> future =
                jobMonitorFuture;

        return future != null
                && !future.isCancelled()
                && !future.isDone();
    }

    /**
     * Updates the matching tree node without rebuilding the entire
     * Jobs tree every five seconds.
     * <p>
     * This is intentionally much cheaper than refreshJobs().
     */
    private void updateJobNode(
            final Job updatedJob) {

        DefaultMutableTreeNode node =
                findJobNode(updatedJob);

        if (node == null) {
            return;
        }

        node.setUserObject(
                new JobNode(updatedJob));

        jobsModel.nodeChanged(node);
    }

    private void loadJcl(Project project) {

        Job job =
                selectedJob(project);

        if (job == null) {
            return;
        }

        setState(
                "Loading JCL...");

        runBackground(project, () -> {

            String jcl =
                    new JobService(
                            ZoweConnectionProvider.current())
                            .getJcl(job);

            SwingUtilities.invokeLater(() -> {

                jobDetails.setText(jcl);

                jobDetails.setCaretPosition(0);

                setState(
                        "Loaded JCL for "
                                + job.getJobName()
                                + " / "
                                + job.getJobId());
            });
        });
    }

    private void loadSpoolFiles(Project project) {

        Job job =
                selectedJob(project);

        if (job == null) {
            return;
        }

        setState(
                "Loading spool files...");

        runBackground(project, () -> {

            List<JobFile> files =
                    new JobService(
                            ZoweConnectionProvider.current())
                            .getSpoolFiles(job);

            SwingUtilities.invokeLater(() -> {

                DefaultMutableTreeNode jobNode =
                        findJobNode(job);

                if (jobNode != null) {

                    jobNode.removeAllChildren();

                    for (JobFile file : files) {

                        jobNode.add(
                                new DefaultMutableTreeNode(
                                        new SpoolNode(
                                                job,
                                                file)));
                    }

                    jobsModel.reload(jobNode);

                    jobsTree.expandPath(
                            new TreePath(
                                    jobNode.getPath()));
                }

                setState(
                        "Loaded "
                                + files.size()
                                + " spool files.");
            });
        });
    }

    private void loadSelectedSpool(Project project) {

        DefaultMutableTreeNode node =
                selectedNode(jobsTree);

        if (node == null
                || !(node.getUserObject()
                instanceof SpoolNode spool)) {

            Messages.showInfoMessage(
                    project,
                    "Select a spool file first.",
                    "z/OS Workbench for Zowe");

            return;
        }

        setState(
                "Loading spool content...");

        runBackground(project, () -> {

            String content =
                    new JobService(
                            ZoweConnectionProvider.current())
                            .getSpool(spool.file);

            SwingUtilities.invokeLater(() -> {

                jobDetails.setText(content);

                jobDetails.setCaretPosition(0);

                setJobPreviewExpanded(true);

                setState(
                        "Loaded spool content.");
            });
        });
    }

    private void openSpoolInEditor(Project project) {
        DefaultMutableTreeNode node = selectedNode(jobsTree);
        if (node == null || !(node.getUserObject() instanceof SpoolNode spool)) {
            Messages.showInfoMessage(
                    project,
                    "Select a spool file first.",
                    "z/OS Workbench for Zowe");
            return;
        }
        openSpoolInEditor(project, spool);
    }

    private void openSpoolInEditor(Project project, SpoolNode spool) {
        if (spool == null || spool.file == null) {
            Messages.showInfoMessage(
                    project,
                    "Select a spool file first.",
                    "z/OS Workbench for Zowe");
            return;
        }

        JobFile file = spool.file;
        setState("Downloading spool " + file.getDdName() + " for " + file.getJobName() + " / " + file.getJobId() + "...");

        runBackground(project, () -> {
            String content =
                    new JobService(ZoweConnectionProvider.current())
                            .getSpool(file);

            SwingUtilities.invokeLater(() -> {
                remoteEditors.openSpool(file, content);
                setState("Opened spool " + file.getDdName() + " in editor.");
            });
        });
    }

    private void maybeShowJobPopup(
            Project project,
            MouseEvent event) {

        if (!event.isPopupTrigger()) {
            return;
        }

        TreePath path =
                jobsTree.getPathForLocation(
                        event.getX(),
                        event.getY());

        if (path == null) {
            return;
        }

        jobsTree.setSelectionPath(path);

        DefaultMutableTreeNode node =
                (DefaultMutableTreeNode)
                        path.getLastPathComponent();

        Object value =
                node.getUserObject();

        if (value instanceof SpoolNode spool) {
            JPopupMenu menu = new JPopupMenu();

            JMenuItem openEditor = new JMenuItem("Open in Main Editor", AllIcons.Actions.MenuOpen);
            JMenuItem saveSpool = new JMenuItem("Download Spool As...", AllIcons.Actions.Download);
            JMenuItem viewSpool = new JMenuItem("View Spool Content", AllIcons.Actions.Preview);

            openEditor.addActionListener(e -> openSpoolInEditor(project, spool));
            saveSpool.addActionListener(e -> saveSelectedSpool(project));
            viewSpool.addActionListener(e -> loadSelectedSpool(project));

            menu.add(openEditor);
            menu.add(saveSpool);
            menu.addSeparator();
            menu.add(viewSpool);

            menu.show(
                    event.getComponent(),
                    event.getX(),
                    event.getY());
        } else if (value instanceof JobNode jobNode) {
            JPopupMenu menu = new JPopupMenu();

            JMenuItem loadSpools = new JMenuItem("Load Spool Files", AllIcons.Nodes.Folder);
            JMenuItem showJcl = new JMenuItem("Show JCL", AllIcons.Actions.ShowCode);

            loadSpools.addActionListener(e -> loadSpoolFiles(project));
            showJcl.addActionListener(e -> loadJcl(project));

            menu.add(loadSpools);
            menu.add(showJcl);

            Job job = jobNode.job;
            String status = job != null ? job.getStatus() : null;
            boolean isOutput = status != null && "OUTPUT".equalsIgnoreCase(status.trim());

            menu.addSeparator();
            if (isOutput) {
                JMenuItem purgeJob = new JMenuItem("Purge Job", AllIcons.Actions.GC);
                purgeJob.addActionListener(e -> purgeSelectedJob(project, job));
                menu.add(purgeJob);
            } else {
                JMenuItem cancelJob = new JMenuItem("Cancel Job", AllIcons.Actions.Cancel);
                cancelJob.addActionListener(e -> cancelSelectedJob(project, job));
                menu.add(cancelJob);
            }

            menu.show(
                    event.getComponent(),
                    event.getX(),
                    event.getY());
        }
    }

    private void cancelSelectedJob(
            final Project project,
            final Job job) {

        if (job == null) {
            return;
        }

        int result = Messages.showYesNoDialog(
                project,
                "Are you sure you want to cancel job '" + job.getJobName() + "' (" + job.getJobId() + ")?",
                "Cancel Job",
                "Cancel Job",
                "Keep Job",
                Messages.getQuestionIcon());

        if (result != Messages.YES) {
            return;
        }

        setState("Cancelling job " + job.getJobName() + " (" + job.getJobId() + ")...");

        runBackground(project, () -> {
            new JobService(ZoweConnectionProvider.current()).cancelJob(job);

            SwingUtilities.invokeLater(() -> {
                setState("Cancelled job " + job.getJobName() + " (" + job.getJobId() + ").");
                refreshJobs(project);
            });
        });
    }

    private void purgeSelectedJob(
            final Project project,
            final Job job) {

        if (job == null) {
            return;
        }

        int result = Messages.showYesNoDialog(
                project,
                "Are you sure you want to purge job '" + job.getJobName() + "' (" + job.getJobId() + ")?\n\nThis will delete all output spool files for this job on z/OS.",
                "Purge Job",
                "Purge",
                "Keep",
                Messages.getQuestionIcon());

        if (result != Messages.YES) {
            return;
        }

        setState("Purging job " + job.getJobName() + " (" + job.getJobId() + ")...");

        runBackground(project, () -> {
            new JobService(ZoweConnectionProvider.current()).deleteJob(job);

            SwingUtilities.invokeLater(() -> {
                DefaultMutableTreeNode node = findJobNode(job);
                if (node != null) {
                    jobsModel.removeNodeFromParent(node);
                }
                setState("Purged job " + job.getJobName() + " (" + job.getJobId() + ").");
            });
        });
    }

    /**
     * Saves the selected spool file to the local file system.
     * <p>
     * Spool content is retrieved through the Zowe Client Java SDK and
     * written as UTF-8 text.
     */
    private void saveSelectedSpool(
            final Project project) {

        final DefaultMutableTreeNode node =
                selectedNode(jobsTree);

        if (node == null
                || !(node.getUserObject()
                instanceof SpoolNode spool)) {

            Messages.showInfoMessage(
                    project,
                    "Select a spool file first.",
                    "z/OS Workbench for Zowe");

            return;
        }

        final JobFile file =
                spool.file;

        /*
         * Build a useful default file name such as:
         *
         * MYJOB_JOB12345_JESMSGLG.txt
         */
        final String defaultFileName =
                safeFileName(
                        file.getJobName()
                                + "_"
                                + file.getJobId()
                                + "_"
                                + file.getDdName()
                                + ".txt");

        final JFileChooser chooser =
                new JFileChooser();

        chooser.setDialogTitle(
                "Save Spool Output");

        chooser.setSelectedFile(
                new java.io.File(
                        defaultFileName));

        chooser.setFileFilter(
                new FileNameExtensionFilter(
                        "Text Files (*.txt)",
                        "txt"));

        final int result =
                chooser.showSaveDialog(
                        jobsTree);

        if (result
                != JFileChooser.APPROVE_OPTION) {

            return;
        }

        final Path destination =
                chooser.getSelectedFile()
                        .toPath();

        /*
         * Confirm before replacing an existing local file.
         */
        if (Files.exists(destination)) {

            final int overwrite =
                    Messages.showYesNoDialog(
                            project,
                            "The file already exists:\n\n"
                                    + destination
                                    + "\n\nOverwrite it?",
                            "Save Spool Output",
                            Messages.getQuestionIcon());

            if (overwrite
                    != Messages.YES) {

                return;
            }
        }

        setState(
                "Downloading spool "
                        + file.getDdName()
                        + " for "
                        + file.getJobName()
                        + " / "
                        + file.getJobId()
                        + "...");

        /*
         * The z/OSMF request and local disk write both run off the EDT.
         */
        runBackground(project, () -> {

            final String content =
                    new JobService(
                            ZoweConnectionProvider.current())
                            .getSpool(file);

            Files.writeString(
                    destination,
                    content,
                    StandardCharsets.UTF_8);

            SwingUtilities.invokeLater(() -> {

                setState(
                        "Saved spool "
                                + file.getDdName()
                                + " to "
                                + destination
                                + ".");

                Messages.showInfoMessage(
                        project,
                        "Spool output saved to:\n\n"
                                + destination,
                        "z/OS Workbench for Zowe");
            });
        });
    }

    /**
     * Removes characters that are unsafe in Windows/local file names.
     */
    private static String safeFileName(
            final String value) {

        if (value == null
                || value.isBlank()) {

            return "spool.txt";
        }

        return value.replaceAll(
                "[\\\\/:*?\"<>|]",
                "_");
    }

    private void showSelectedJobNode() {

        DefaultMutableTreeNode node =
                selectedNode(jobsTree);

        if (node == null) {
            return;
        }

        Object value =
                node.getUserObject();

        if (value instanceof JobNode j) {

            jobDetails.setText(
                    formatJob(j.job));

        } else if (value
                instanceof SpoolNode s) {

            jobDetails.setText(
                    formatJobFile(s.file));
        }

        jobDetails.setCaretPosition(0);
    }

    // -------------------------------------------------------------------------
    // Data Sets
    // -------------------------------------------------------------------------

    private JComponent createDataSetsPanel(Project project) {

        JPanel panel =
                new JPanel(
                        new BorderLayout(
                                4,
                                4));

        JPanel toolbar =
                new JPanel(
                        new WrapLayout(
                                FlowLayout.LEFT,
                                4,
                                2));

        JButton search =
                new JButton("Search");

        JButton members =
                new JButton("Members");

        JButton open =
                new JButton("Open");

        dsnMask.setEditable(true);
        dsnMask.setPreferredSize(new Dimension(220, dsnMask.getPreferredSize().height));
        dsnMask.setMinimumSize(new Dimension(80, dsnMask.getPreferredSize().height));

        dsnMask.setToolTipText(
                "Data set mask, for example USER.* or USER.JCL (Right-click to manage history)");

        updateDsnMaskHistoryModel(null);

        Component editorComponent = dsnMask.getEditor() != null ? dsnMask.getEditor().getEditorComponent() : null;
        if (editorComponent instanceof JTextField textField) {
            textField.addActionListener(e -> searchDataSets(project));
        }

        dsnMask.addActionListener(e -> {
            if ("comboBoxEdited".equals(e.getActionCommand())) {
                searchDataSets(project);
            }
        });

        JButton deleteMask = new JButton(AllIcons.Actions.GC);
        deleteMask.setToolTipText("Delete selected mask from history");
        deleteMask.addActionListener(e -> deleteSelectedDsnMask(project));

        JPopupMenu maskMenu = new JPopupMenu();
        JMenuItem deleteItem = new JMenuItem("Delete Selected Mask from History", AllIcons.Actions.GC);
        deleteItem.addActionListener(e -> deleteSelectedDsnMask(project));
        JMenuItem clearAllItem = new JMenuItem("Clear All Mask History");
        clearAllItem.addActionListener(e -> clearDsnMaskHistory(project));
        maskMenu.add(deleteItem);
        maskMenu.add(clearAllItem);

        MouseAdapter popupListener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }

            private void maybeShowPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    maskMenu.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        };
        dsnMask.addMouseListener(popupListener);
        if (editorComponent != null) {
            editorComponent.addMouseListener(popupListener);
        }

        search.addActionListener(
                e -> searchDataSets(project));

        members.addActionListener(
                e -> loadMembers(project));

        open.addActionListener(
                e -> openDataSetSelection(project));

        toolbar.add(
                new JBLabel("Mask:"));

        toolbar.add(dsnMask);
        toolbar.add(deleteMask);
        toolbar.add(search);
        toolbar.add(members);
        toolbar.add(open);

        dsnTree.setRootVisible(true);
        ToolTipManager.sharedInstance().registerComponent(dsnTree);

        dsnTree.setCellRenderer(
                new DefaultTreeCellRenderer() {

                    @Override
                    public Component getTreeCellRendererComponent(
                            JTree tree,
                            Object value,
                            boolean sel,
                            boolean expanded,
                            boolean leaf,
                            int row,
                            boolean hasFocus) {

                        Component c =
                                super.getTreeCellRendererComponent(
                                        tree,
                                        value,
                                        sel,
                                        expanded,
                                        leaf,
                                        row,
                                        hasFocus);

                        if (value instanceof DefaultMutableTreeNode node) {

                            Object userObject =
                                    node.getUserObject();

                            if (userObject instanceof DatasetNode datasetNode) {

                                if (isPds(datasetNode.dataset)) {
                                    setIcon(AllIcons.Nodes.Folder);
                                } else {
                                    setIcon(AllIcons.FileTypes.Text);
                                }

                            } else if (userObject instanceof MemberNode) {

                                setIcon(AllIcons.Nodes.C_public);
                            }
                        }

                        if (c instanceof JComponent jc) {
                            jc.setToolTipText(getText());
                        }

                        return c;
                    }
                });

        dsnTree.addTreeSelectionListener(
                e -> showSelectedDsnNode());

        /*
         * Dataset tree mouse processing.
         *
         * Double-click continues to open the selected resource.
         *
         * Right-click on a PDS/PDSE member provides:
         *
         *   Open
         *   Submit as Job
         */
        dsnTree.addMouseListener(
                new MouseAdapter() {

                    @Override
                    public void mousePressed(
                            MouseEvent e) {

                        maybeShowDataSetPopup(
                                project,
                                e);
                    }

                    @Override
                    public void mouseReleased(
                            MouseEvent e) {

                        maybeShowDataSetPopup(
                                project,
                                e);
                    }

                    @Override
                    public void mouseClicked(
                            MouseEvent e) {

                        if (e.getClickCount() == 2
                                && SwingUtilities
                                .isLeftMouseButton(e)) {

                            handleDsnTreeDoubleClick(project);
                        }
                    }
                });

        dsnSplitter.setFirstComponent(
                new JBScrollPane(dsnTree));

        /* Preview pane is collapsed by default; toggle strip on the right edge. */
        dsnPreviewToggle.setMargin(new Insets(2, 2, 2, 2));
        dsnPreviewToggle.setFocusable(false);
        dsnPreviewToggle.addActionListener(
                e -> setDsnPreviewExpanded(!dsnPreviewExpanded));

        JPanel previewStrip =
                new JPanel(new BorderLayout());
        previewStrip.add(dsnPreviewToggle, BorderLayout.NORTH);

        setDsnPreviewExpanded(false);

        JPanel center =
                new JPanel(new BorderLayout());
        center.add(dsnSplitter, BorderLayout.CENTER);
        center.add(previewStrip, BorderLayout.EAST);

        panel.add(
                toolbar,
                BorderLayout.NORTH);

        panel.add(
                center,
                BorderLayout.CENTER);

        return panel;
    }

    private void setDsnPreviewExpanded(boolean expanded) {

        dsnPreviewExpanded = expanded;

        dsnSplitter.setSecondComponent(
                expanded ? dsnPreviewScroll : null);

        styleToggle(dsnPreviewToggle, expanded);

        dsnSplitter.revalidate();
        dsnSplitter.repaint();
    }

    /**
     * Display the context menu when a PDS/PDSE member is right-clicked.
     */
    private void maybeShowDataSetPopup(
            Project project,
            MouseEvent event) {

        if (!event.isPopupTrigger()) {
            return;
        }

        TreePath path =
                dsnTree.getPathForLocation(
                        event.getX(),
                        event.getY());

        if (path == null) {
            return;
        }

        /*
         * Right-click should also select the node beneath the mouse.
         */
        dsnTree.setSelectionPath(path);

        DefaultMutableTreeNode node =
                (DefaultMutableTreeNode)
                        path.getLastPathComponent();

        Object value =
                node.getUserObject();

        if (value instanceof DatasetNode datasetNode) {
            if (isPds(datasetNode.dataset)) {
                JPopupMenu menu = new JPopupMenu();
                JMenuItem createMember = new JMenuItem("Create Member...", AllIcons.General.Add);
                JMenuItem refreshMembers = new JMenuItem("Refresh Members", AllIcons.Actions.Refresh);

                createMember.addActionListener(e -> createMemberInDataset(project, node, datasetNode));
                refreshMembers.addActionListener(e -> loadMembersForNode(project, node, datasetNode));

                menu.add(createMember);
                menu.add(refreshMembers);
                menu.show(event.getComponent(), event.getX(), event.getY());
            }
            return;
        }

        /*
         * Submit-as-job is intentionally exposed only for members.
         *
         * We do not attempt to parse the member looking for a JOB card.
         * JES/z/OSMF remains responsible for validating the JCL.
         */
        if (!(value instanceof MemberNode member)) {
            return;
        }

        JPopupMenu menu =
                new JPopupMenu();

        JMenuItem open =
                new JMenuItem("Open", AllIcons.Actions.MenuOpen);

        JMenuItem submit =
                new JMenuItem("Submit as Job", AllIcons.Actions.Execute);

        JMenuItem rename =
                new JMenuItem("Rename...", AllIcons.Actions.Edit);

        JMenuItem delete =
                new JMenuItem("Delete...", AllIcons.Actions.GC);

        open.addActionListener(
                e -> openDataSetSelection(project));

        submit.addActionListener(
                e -> submitMemberAsJob(
                        project,
                        member));

        rename.addActionListener(
                e -> renameMemberSelection(project, node, member));

        delete.addActionListener(
                e -> deleteMemberSelection(project, node, member));

        menu.add(open);
        menu.add(submit);
        menu.addSeparator();
        menu.add(rename);
        menu.add(delete);

        menu.show(
                event.getComponent(),
                event.getX(),
                event.getY());
    }

    private void createMemberInDataset(Project project, DefaultMutableTreeNode node, DatasetNode datasetNode) {
        String dataSet = datasetNode.dataset.getDsname();

        String input = Messages.showInputDialog(
                project,
                "Enter new member name to create in " + dataSet + ":",
                "Create Member",
                Messages.getQuestionIcon(),
                "",
                null);

        if (input == null) {
            return;
        }

        String newMember = input.trim().toUpperCase(Locale.ROOT);
        if (newMember.isBlank()) {
            return;
        }

        if (newMember.length() > 8) {
            Messages.showErrorDialog(project, "Member name cannot exceed 8 characters.", "Invalid Member Name");
            return;
        }

        if (!newMember.matches("^[A-Z@#\\$][A-Z0-9@#\\$]{0,7}$")) {
            Messages.showErrorDialog(
                    project,
                    "Invalid z/OS member name '" + newMember + "'. Must start with A-Z, @, #, or $ and contain up to 8 alphanumeric/national characters.",
                    "Invalid Member Name");
            return;
        }

        setState("Creating member " + newMember + " in " + dataSet + "...");

        runBackground(project, () -> {
            try {
                new DataSetService(ZoweConnectionProvider.current()).createMember(dataSet, newMember);
                SwingUtilities.invokeLater(() -> {
                    loadMembersForNode(project, node, datasetNode);
                    setState("Created member " + newMember + " in " + dataSet + ".");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setState("Could not create member " + newMember + ": " + ex.getMessage());
                    Messages.showErrorDialog(project,
                            "Could not create member " + newMember + " in " + dataSet + ".\n\n" + ex.getMessage(),
                            "Create Member Failed");
                });
            }
        });
    }

    private void renameMemberSelection(Project project, DefaultMutableTreeNode node, MemberNode memberNode) {
        String dataSet = memberNode.dataSetName;
        String oldMember = memberNode.member.getMember();

        String input = Messages.showInputDialog(
                project,
                "Enter new name for member " + dataSet + "(" + oldMember + "):",
                "Rename Member",
                Messages.getQuestionIcon(),
                oldMember,
                null);

        if (input == null) {
            return;
        }

        String newMember = input.trim().toUpperCase(Locale.ROOT);
        if (newMember.isBlank() || newMember.equals(oldMember)) {
            return;
        }

        if (newMember.length() > 8) {
            Messages.showErrorDialog(project, "Member name cannot exceed 8 characters.", "Invalid Member Name");
            return;
        }

        setState("Renaming member " + oldMember + " to " + newMember + " in " + dataSet + "...");

        runBackground(project, () -> {
            try {
                new DataSetService(ZoweConnectionProvider.current()).renameMember(dataSet, oldMember, newMember);
                SwingUtilities.invokeLater(() -> {
                    DefaultMutableTreeNode parentNode = (DefaultMutableTreeNode) node.getParent();
                    if (parentNode != null && parentNode.getUserObject() instanceof DatasetNode parentDatasetNode) {
                        loadMembersForNode(project, parentNode, parentDatasetNode);
                    }
                    setState("Renamed member " + oldMember + " to " + newMember + " in " + dataSet + ".");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setState("Could not rename member " + oldMember + ": " + ex.getMessage());
                    Messages.showErrorDialog(project,
                            "Could not rename member " + oldMember + " to " + newMember + " in " + dataSet + ".\n\n" + ex.getMessage(),
                            "Rename Member Failed");
                });
            }
        });
    }

    private void deleteMemberSelection(Project project, DefaultMutableTreeNode node, MemberNode memberNode) {
        String dataSet = memberNode.dataSetName;
        String memberName = memberNode.member.getMember();

        int choice = Messages.showYesNoDialog(
                project,
                "Are you sure you want to delete member '" + memberName + "' from '" + dataSet + "'?\n\nThis action cannot be undone.",
                "Delete Member Confirmation",
                "Delete",
                "Cancel",
                Messages.getWarningIcon());

        if (choice != Messages.YES) {
            return;
        }

        setState("Deleting member " + memberName + " from " + dataSet + "...");

        runBackground(project, () -> {
            try {
                new DataSetService(ZoweConnectionProvider.current()).deleteMember(dataSet, memberName);
                SwingUtilities.invokeLater(() -> {
                    dsnModel.removeNodeFromParent(node);
                    dsnDetails.setText("");
                    setState("Deleted member " + memberName + " from " + dataSet + ".");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setState("Could not delete member " + memberName + ": " + ex.getMessage());
                    Messages.showErrorDialog(project,
                            "Could not delete member " + memberName + " from " + dataSet + ".\n\n" + ex.getMessage(),
                            "Delete Member Failed");
                });
            }
        });
    }

    /**
     * Submit the selected PDS/PDSE member as JCL.
     * <p>
     * Example:
     * <p>
     * USER.JCL(MYJOB)
     */
    private void submitMemberAsJob(
            Project project,
            MemberNode member) {

        submitDataSetAsJob(
                project,
                member.qualifiedName());
    }

    private String getDsnMaskText() {
        Object item = dsnMask.getEditor() != null ? dsnMask.getEditor().getItem() : dsnMask.getSelectedItem();
        return item != null ? item.toString().trim() : "";
    }

    private void updateDsnMaskHistoryModel(String currentMask) {
        List<String> history = ZoweConnectionSettings.getInstance().getDsnMaskHistory();
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        for (String item : history) {
            model.addElement(item);
        }
        dsnMask.setModel(model);
        if (currentMask != null && !currentMask.isBlank()) {
            dsnMask.setSelectedItem(currentMask);
            if (dsnMask.getEditor() != null) {
                dsnMask.getEditor().setItem(currentMask);
            }
        } else if (!history.isEmpty()) {
            dsnMask.setSelectedItem(history.get(0));
            if (dsnMask.getEditor() != null) {
                dsnMask.getEditor().setItem(history.get(0));
            }
        }
    }

    private void deleteSelectedDsnMask(Project project) {
        String mask = getDsnMaskText();
        if (mask.isEmpty()) {
            return;
        }
        ZoweConnectionSettings.getInstance().removeDsnMaskFromHistory(mask);
        updateDsnMaskHistoryModel(null);
        setState("Deleted mask '" + mask + "' from history.");
    }

    private void clearDsnMaskHistory(Project project) {
        int choice = Messages.showYesNoDialog(
                project,
                "Are you sure you want to clear all dataset mask history?",
                "Clear Mask History",
                Messages.getQuestionIcon());
        if (choice == Messages.YES) {
            ZoweConnectionSettings.getInstance().clearDsnMaskHistory();
            updateDsnMaskHistoryModel(null);
            if (dsnMask.getEditor() != null) {
                dsnMask.getEditor().setItem("");
            }
            setState("Cleared dataset mask history.");
        }
    }

    private void searchDataSets(Project project) {

        String mask = getDsnMaskText();

        if (mask.isEmpty()) {

            Messages.showInfoMessage(
                    project,
                    "Enter a data set mask first.",
                    "z/OS Workbench for Zowe");

            return;
        }

        ZoweConnectionSettings.getInstance().addDsnMaskToHistory(mask);
        updateDsnMaskHistoryModel(mask);

        setState(
                "Searching data sets: "
                        + mask);

        runBackground(project, () -> {

            List<Dataset> items =
                    new DataSetService(
                            ZoweConnectionProvider.current())
                            .list(mask);

            SwingUtilities.invokeLater(() -> {

                dsnRoot.removeAllChildren();

                for (Dataset item : items) {

                    dsnRoot.add(
                            new DefaultMutableTreeNode(
                                    new DatasetNode(item)));
                }

                dsnModel.reload();

                dsnTree.expandRow(0);

                setState(
                        "Found "
                                + items.size()
                                + " data sets.");
            });
        });
    }

    private void loadMembers(Project project) {

        DefaultMutableTreeNode node =
                selectedNode(dsnTree);

        if (node == null
                || !(node.getUserObject()
                instanceof DatasetNode datasetNode)) {

            Messages.showInfoMessage(
                    project,
                    "Select a partitioned data set first.",
                    "z/OS Workbench for Zowe");

            return;
        }

        Dataset dataset = datasetNode.dataset;
        if (isArchivedDataset(dataset)) {
            showArchivedMessage(project, dataset.getDsname());
            return;
        }

        if (!isPds(dataset)) {
            String dsn = dataset.getDsname();
            String reason = isSequential(dataset) ? "sequential" : "non-partitioned";
            if (dataset.getDsorg() == null || dataset.getDsorg().isBlank()) {
                reason = "of unknown type / unclassified DSORG";
            }
            setState("Data set " + dsn + " is " + reason + ". Nothing to display for members.");
            dsnDetails.setText(formatDataset(dataset));
            Messages.showInfoMessage(
                    project,
                    "Cannot load members for " + dsn + " because it is " + reason + ".",
                    "z/OS Workbench for Zowe");
            return;
        }

        loadMembersForNode(project, node, datasetNode);
    }

    private void loadMembersForNode(
            Project project,
            DefaultMutableTreeNode node,
            DatasetNode datasetNode) {

        String dsn =
                datasetNode.dataset
                        .getDsname();

        setState(
                "Loading members for "
                        + dsn
                        + "...");

        runBackground(project, () -> {

            try {
                List<Member> members =
                        new DataSetService(
                                ZoweConnectionProvider.current())
                                .members(dsn);

                SwingUtilities.invokeLater(() -> {

                    node.removeAllChildren();

                    for (Member member : members) {

                        node.add(
                                new DefaultMutableTreeNode(
                                        new MemberNode(
                                                dsn,
                                                member)));
                    }

                    dsnModel.reload(node);

                    dsnTree.expandPath(
                            new TreePath(
                                    node.getPath()));

                    setState(
                            "Loaded "
                                    + members.size()
                                    + " members from "
                                    + dsn
                                    + ".");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (isArchivedError(ex)) {
                        showArchivedMessage(project, dsn);
                    } else {
                        setState("Could not load members for " + dsn + ": " + ex.getMessage());
                        Messages.showErrorDialog(project,
                                "Could not load members for " + dsn + ".\n\n" + ex.getMessage(),
                                "z/OS Workbench for Zowe");
                    }
                });
            }
        });
    }

    private void openDataSetSelection(
            Project project) {

        DefaultMutableTreeNode node =
                selectedNode(dsnTree);

        if (node == null) {
            return;
        }

        Object value =
                node.getUserObject();

        if (value instanceof MemberNode member) {

            openDataSetTarget(project, member.qualifiedName());

        } else if (value instanceof DatasetNode datasetNode) {

            Dataset dataset = datasetNode.dataset;
            if (isArchivedDataset(dataset)) {
                showArchivedMessage(project, dataset.getDsname());
                return;
            }
            if (isPds(dataset)) {
                Messages.showInfoMessage(
                        project,
                        "Data set " + dataset.getDsname() + " is a Partitioned Data Set (PDS). Select or double-click a member inside it to open.",
                        "z/OS Workbench for Zowe");
            } else if (isSequential(dataset) || dataset.getDsorg() == null || dataset.getDsorg().isBlank()) {
                openDataSetTarget(project, dataset.getDsname());
            } else {
                String dsorg = dataset.getDsorg();
                setState("Nothing to display for non-sequential and non-partitioned data set " + dataset.getDsname() + ".");
                Messages.showInfoMessage(
                        project,
                        "Nothing to display for non-sequential and non-partitioned data set " + dataset.getDsname() + " (DSORG: " + dsorg + ").",
                        "z/OS Workbench for Zowe");
            }
        }
    }

    private void openDataSetTarget(Project project, String target) {

        setState(
                "Opening "
                        + target
                        + "...");

        runBackground(project, () -> {

            try {
                String content =
                        new DataSetService(
                                ZoweConnectionProvider.current())
                                .read(target);

                SwingUtilities.invokeLater(() -> {

                    remoteEditors.openDataSet(
                            target,
                            content);

                    setState(
                            "Opened "
                                    + target
                                    + " in an IntelliJ editor tab. "
                                    + "Ctrl+S / Save All writes changes to z/OS.");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (isArchivedError(ex)) {
                        showArchivedMessage(project, target);
                    } else {
                        setState("Could not open " + target + ": " + ex.getMessage());
                        Messages.showErrorDialog(project,
                                "Could not open " + target + ".\n\n" + ex.getMessage(),
                                "z/OS Workbench for Zowe");
                    }
                });
            }
        });
    }

    private void handleDsnTreeDoubleClick(Project project) {
        DefaultMutableTreeNode node = selectedNode(dsnTree);
        if (node == null) {
            return;
        }

        Object value = node.getUserObject();

        if (value instanceof DatasetNode datasetNode) {
            Dataset dataset = datasetNode.dataset;
            if (isArchivedDataset(dataset)) {
                showArchivedMessage(project, dataset.getDsname());
                return;
            }
            if (isPds(dataset)) {
                loadMembersForNode(project, node, datasetNode);
            } else if (isSequential(dataset) || dataset.getDsorg() == null || dataset.getDsorg().isBlank()) {
                openDataSetTarget(project, dataset.getDsname());
            } else {
                String dsorg = dataset.getDsorg();
                setState("Nothing to display for non-sequential and non-partitioned data set " + dataset.getDsname() + ".");
                Messages.showInfoMessage(
                        project,
                        "Nothing to display for non-sequential and non-partitioned data set " + dataset.getDsname() + " (DSORG: " + dsorg + ").",
                        "z/OS Workbench for Zowe");
            }
        } else if (value instanceof MemberNode) {
            openDataSetSelection(project);
        }
    }

    private void showSelectedDsnNode() {

        DefaultMutableTreeNode node =
                selectedNode(dsnTree);

        if (node == null) {
            return;
        }

        Object value =
                node.getUserObject();

        if (value instanceof DatasetNode datasetNode) {

            dsnDetails.setText(
                    formatDataset(
                            datasetNode.dataset));

        } else if (value instanceof MemberNode memberNode) {

            dsnDetails.setText(
                    formatMember(
                            memberNode.member));
        }

        dsnDetails.setCaretPosition(0);
    }

    // -------------------------------------------------------------------------
    // USS
    // -------------------------------------------------------------------------

    private JComponent createUssPanel(
            Project project) {

        JPanel panel =
                new JPanel(
                        new BorderLayout(
                                4,
                                4));

        JPanel north =
                new JPanel();

        north.setLayout(
                new BoxLayout(
                        north,
                        BoxLayout.Y_AXIS));

        JPanel pathToolbar =
                new JPanel(
                        new WrapLayout(
                                FlowLayout.LEFT,
                                4,
                                2));

        JPanel filterToolbar =
                new JPanel(
                        new WrapLayout(
                                FlowLayout.LEFT,
                                4,
                                2));

        JButton list =
                new JButton("List");

        JButton open =
                new JButton("Open");

        JButton up =
                new JButton("Up");

        JButton clearFilter =
                new JButton("Clear");

        ussPath.setPreferredSize(new Dimension(260, ussPath.getPreferredSize().height));
        ussPath.setMinimumSize(new Dimension(80, ussPath.getPreferredSize().height));
        ussFilter.setColumns(20);

        ussPath.setToolTipText(
                "USS Directory Path, for example /u/users/fg892105 or /usr/lpp (Right-click to manage history)");
        ussPath.setHistory(ZoweConnectionSettings.getInstance().getUssPathHistory());

        JButton deletePath = new JButton(AllIcons.Actions.GC);
        deletePath.setToolTipText("Delete selected path from history");
        deletePath.addActionListener(e -> deleteSelectedUssPath(project));

        JPopupMenu pathMenu = new JPopupMenu();
        JMenuItem deletePathItem = new JMenuItem("Delete Selected Path from History", AllIcons.Actions.GC);
        deletePathItem.addActionListener(e -> deleteSelectedUssPath(project));
        JMenuItem clearPathsItem = new JMenuItem("Clear All Path History");
        clearPathsItem.addActionListener(e -> clearUssPathHistory(project));
        pathMenu.add(deletePathItem);
        pathMenu.add(clearPathsItem);

        MouseAdapter pathPopupListener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }

            private void maybeShowPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    pathMenu.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        };
        ussPath.addMouseListener(pathPopupListener);
        if (ussPath.getEditor() != null) {
            ussPath.getEditor().getEditorComponent().addMouseListener(pathPopupListener);
        }
        ussFilter.setToolTipText("Filter files/directories by name or pattern, for example *.sh, log, or config*");

        list.addActionListener(
                e -> listUss(
                        project,
                        ussPath.getText()));

        open.addActionListener(
                e -> openUssSelection(project));

        up.addActionListener(e -> {

            String parent =
                    parentPath(
                            ussPath.getText());

            ussPath.setText(parent);

            listUss(
                    project,
                    parent);
        });

        // Fires on Enter in the editor or when a history entry is picked; ignored for programmatic updates.
        ussPath.addActionListener(e -> {
            if (!ussPath.isAdjusting()) {
                listUss(project, ussPath.getText());
            }
        });

        ussFilter.addActionListener(
                e -> listUss(
                        project,
                        ussPath.getText()));

        clearFilter.addActionListener(e -> {
            ussFilter.setText("");
            if (!currentUssItems.isEmpty()) {
                applyUssFilter();
            } else {
                listUss(project, ussPath.getText());
            }
        });

        ussFilter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyUssFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyUssFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyUssFilter();
            }
        });

        pathToolbar.add(
                new JBLabel("Path:"));
        pathToolbar.add(ussPath);
        pathToolbar.add(deletePath);
        pathToolbar.add(list);
        pathToolbar.add(up);
        pathToolbar.add(open);

        filterToolbar.add(
                new JBLabel("Filter:"));
        filterToolbar.add(ussFilter);
        filterToolbar.add(clearFilter);

        north.add(pathToolbar);
        north.add(filterToolbar);

        ussTree.setRootVisible(true);
        ToolTipManager.sharedInstance().registerComponent(ussTree);
        ussTree.setCellRenderer(
                new DefaultTreeCellRenderer() {
                    @Override
                    public Component getTreeCellRendererComponent(
                            JTree tree,
                            Object value,
                            boolean sel,
                            boolean expanded,
                            boolean leaf,
                            int row,
                            boolean hasFocus) {

                        Component c = super.getTreeCellRendererComponent(
                                tree, value, sel, expanded, leaf, row, hasFocus);

                        if (value instanceof DefaultMutableTreeNode node) {
                            Object userObject = node.getUserObject();
                            if (userObject instanceof UssNode ussNode) {
                                if (ussNode.isDirectory()) {
                                    setIcon(AllIcons.Nodes.Folder);
                                } else {
                                    setIcon(AllIcons.FileTypes.Text);
                                }
                            }
                        }

                        if (c instanceof JComponent jc) {
                            jc.setToolTipText(getText());
                        }

                        return c;
                    }
                });

        ussTree.addTreeSelectionListener(
                e -> showSelectedUssNode());

        ussTree.addMouseListener(
                new MouseAdapter() {

                    @Override
                    public void mousePressed(
                            MouseEvent e) {

                        maybeShowUssPopup(
                                project,
                                e);
                    }

                    @Override
                    public void mouseReleased(
                            MouseEvent e) {

                        maybeShowUssPopup(
                                project,
                                e);
                    }

                    @Override
                    public void mouseClicked(
                            MouseEvent e) {

                        if (e.getClickCount() == 2
                                && SwingUtilities
                                .isLeftMouseButton(e)) {

                            openUssSelection(
                                    project);
                        }
                    }
                });

        JBSplitter splitter =
                new JBSplitter(
                        false,
                        0.35f);

        splitter.setFirstComponent(
                new JBScrollPane(
                        ussTree));

        splitter.setSecondComponent(
                new JBScrollPane(
                        ussDetails));

        panel.add(
                north,
                BorderLayout.NORTH);

        panel.add(
                splitter,
                BorderLayout.CENTER);

        return panel;
    }

    private void deleteSelectedUssPath(Project project) {
        String path = ussPath.getText();
        if (path.isEmpty()) {
            return;
        }
        ZoweConnectionSettings.getInstance().removeUssPathFromHistory(path);
        ussPath.setHistory(ZoweConnectionSettings.getInstance().getUssPathHistory());
        setState("Deleted path '" + path + "' from history.");
    }

    private void clearUssPathHistory(Project project) {
        int choice = Messages.showYesNoDialog(
                project,
                "Are you sure you want to clear all USS path history?",
                "Clear Path History",
                Messages.getQuestionIcon());
        if (choice == Messages.YES) {
            ZoweConnectionSettings.getInstance().clearUssPathHistory();
            ussPath.setHistory(List.of());
            setState("Cleared USS path history.");
        }
    }

    /** Editable combo box holding saved USS paths; programmatic updates do not fire action events. */
    private static final class PathComboBox extends ComboBox<String> {
        private boolean adjusting;

        private PathComboBox(String initial) {
            setEditable(true);
            setText(initial);
        }

        private boolean isAdjusting() {
            return adjusting;
        }

        private String getText() {
            Object item = getEditor() != null ? getEditor().getItem() : getSelectedItem();
            return item != null ? item.toString().trim() : "";
        }

        private void setText(String text) {
            adjusting = true;
            try {
                setSelectedItem(text);
                if (getEditor() != null) {
                    getEditor().setItem(text);
                }
            } finally {
                adjusting = false;
            }
        }

        /** Replaces the dropdown entries while keeping the text currently shown (or the newest entry if blank). */
        private void setHistory(List<String> history) {
            String current = getText();
            adjusting = true;
            try {
                setModel(new DefaultComboBoxModel<>(history.toArray(new String[0])));
            } finally {
                adjusting = false;
            }
            setText(current.isEmpty() && !history.isEmpty() ? history.get(0) : current);
        }
    }

    private void listUss(
            Project project,
            String requestedPath) {

        String path =
                normalizePath(
                        requestedPath);

        ussPath.setText(path);

        String filterText =
                ussFilter.getText().trim();

        setState(
                "Listing USS "
                        + path
                        + (filterText.isEmpty() ? "..." : " with filter '" + filterText + "'..."));

        runBackground(project, () -> {

            List<UnixFile> items =
                    new UssService(
                            ZoweConnectionProvider.current())
                            .list(path, filterText);

            SwingUtilities.invokeLater(() -> {

                currentUssItems = items;
                currentUssPath = path;

                ZoweConnectionSettings.getInstance().addUssPathToHistory(path);
                ussPath.setHistory(ZoweConnectionSettings.getInstance().getUssPathHistory());

                applyUssFilter();
            });
        });
    }

    private void applyUssFilter() {
        String filter = ussFilter.getText().trim();
        ussRoot.removeAllChildren();
        int matchedCount = 0;
        for (UnixFile item : currentUssItems) {
            if (matchesFilter(item, filter)) {
                ussRoot.add(
                        new DefaultMutableTreeNode(
                                new UssNode(
                                        currentUssPath,
                                        item)));
                matchedCount++;
            }
        }
        ussModel.reload();
        if (matchedCount > 0) {
            ussTree.expandRow(0);
        }
        if (filter.isEmpty()) {
            setState(
                    "Loaded "
                            + currentUssItems.size()
                            + " USS entries from "
                            + currentUssPath
                            + ".");
        } else {
            setState(
                    "Showing "
                            + matchedCount
                            + " of "
                            + currentUssItems.size()
                            + " entries matching filter '"
                            + filter
                            + "'.");
        }
    }

    private static boolean matchesFilter(
            UnixFile file,
            String filter) {

        if (filter == null || filter.isBlank()) {
            return true;
        }
        if (file == null || file.getName() == null) {
            return false;
        }

        String fileName = file.getName();
        String pattern = filter.trim();

        if (pattern.contains("*") || pattern.contains("?")) {
            try {
                String regex = "^"
                        + pattern.toLowerCase(Locale.ROOT)
                        .replace(".", "\\.")
                        .replace("*", ".*")
                        .replace("?", ".")
                        + "$";
                return fileName.toLowerCase(Locale.ROOT).matches(regex);
            } catch (Exception ignored) {
            }
        }

        return fileName.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
    }

    private void openUssSelection(
            Project project) {

        DefaultMutableTreeNode node =
                selectedNode(ussTree);

        if (node == null
                || !(node.getUserObject()
                instanceof UssNode item)) {

            return;
        }

        String fullPath =
                item.fullPath();

        if (item.isDirectory()) {

            ussPath.setText(
                    fullPath);

            listUss(
                    project,
                    fullPath);

            return;
        }

        setState(
                "Opening USS file "
                        + fullPath
                        + "...");

        runBackground(project, () -> {

            String content =
                    new UssService(
                            ZoweConnectionProvider.current())
                            .readText(fullPath);

            SwingUtilities.invokeLater(() -> {

                remoteEditors.openUss(
                        fullPath,
                        content);

                setState(
                        "Opened "
                                + fullPath
                                + " in an IntelliJ editor tab. "
                                + "Ctrl+S / Save All writes changes to z/OS.");
            });
        });
    }

    private void maybeShowUssPopup(
            Project project,
            MouseEvent event) {

        if (!event.isPopupTrigger()) {
            return;
        }

        TreePath path =
                ussTree.getPathForLocation(
                        event.getX(),
                        event.getY());

        if (path == null) {
            return;
        }

        ussTree.setSelectionPath(path);

        DefaultMutableTreeNode node =
                (DefaultMutableTreeNode)
                        path.getLastPathComponent();

        Object value =
                node.getUserObject();

        JPopupMenu menu = new JPopupMenu();

        if (value instanceof UssNode item) {
            JMenuItem open = new JMenuItem("Open", AllIcons.Actions.MenuOpen);
            JMenuItem createFile = new JMenuItem("Create File...", AllIcons.General.Add);
            JMenuItem createDir = new JMenuItem("Create Directory...", AllIcons.Actions.NewFolder);
            JMenuItem rename = new JMenuItem("Rename...", AllIcons.Actions.Edit);
            JMenuItem delete = new JMenuItem("Delete...", AllIcons.Actions.GC);
            JMenuItem openWithEncoding = new JMenuItem("Open With Encoding...", AllIcons.Actions.MenuOpen);
            JMenuItem changeTag = new JMenuItem("Change Tag (chtag)...", AllIcons.Actions.Edit);

            open.addActionListener(e -> openUssSelection(project));
            createFile.addActionListener(e -> createUssFileFromSelection(project, item));
            createDir.addActionListener(e -> createUssDirFromSelection(project, item));
            rename.addActionListener(e -> renameUssSelection(project, item));
            delete.addActionListener(e -> deleteUssSelection(project, item));
            changeTag.addActionListener(e -> changeUssTag(project, item));
            openWithEncoding.addActionListener(e -> openUssWithEncoding(project, item));

            menu.add(open);
            menu.add(createFile);
            menu.add(createDir);
            menu.addSeparator();
            if (!item.isDirectory()) {
                menu.add(openWithEncoding);
                menu.add(changeTag);
            }
            menu.add(rename);
            menu.add(delete);
        } else {
            JMenuItem createFile = new JMenuItem("Create File...", AllIcons.General.Add);
            JMenuItem createDir = new JMenuItem("Create Directory...", AllIcons.Actions.NewFolder);

            createFile.addActionListener(e -> createUssFileInDir(project, ussPath.getText()));
            createDir.addActionListener(e -> createUssDirInDir(project, ussPath.getText()));

            menu.add(createFile);
            menu.add(createDir);
        }

        menu.show(
                event.getComponent(),
                event.getX(),
                event.getY());
    }

    private void createUssFileFromSelection(
            Project project,
            UssNode item) {

        String targetDir = item.isDirectory()
                ? item.fullPath()
                : normalizePath(ussPath.getText());

        createUssFileInDir(project, targetDir);
    }

    private void createUssDirFromSelection(
            Project project,
            UssNode item) {

        String targetDir = item.isDirectory()
                ? item.fullPath()
                : normalizePath(ussPath.getText());

        createUssDirInDir(project, targetDir);
    }

    private void createUssFileInDir(
            Project project,
            String targetDir) {

        String dirPath = normalizePath(targetDir);

        String fileName = Messages.showInputDialog(
                project,
                "Enter new file name to create in " + dirPath + ":",
                "Create USS File",
                Messages.getQuestionIcon());

        if (fileName == null || fileName.trim().isEmpty()) {
            return;
        }

        fileName = fileName.trim();

        String newPath = "/".equals(dirPath)
                ? "/" + fileName
                : dirPath + "/" + fileName;

        setState("Creating USS file " + newPath + "...");

        runBackground(project, () -> {
            try {
                new UssService(
                        ZoweConnectionProvider.current())
                        .createFile(newPath);

                SwingUtilities.invokeLater(() -> {
                    setState("Created USS file " + newPath + ".");
                    listUss(project, dirPath);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setState("Could not create USS file " + newPath + ": " + ex.getMessage());
                    Messages.showErrorDialog(
                            project,
                            "Could not create USS file " + newPath + ".\n\n" + ex.getMessage(),
                            "Create USS File Failed");
                });
            }
        });
    }

    private void createUssDirInDir(
            Project project,
            String targetDir) {

        String dirPath = normalizePath(targetDir);

        String dirName = Messages.showInputDialog(
                project,
                "Enter new directory name to create in " + dirPath + ":",
                "Create USS Directory",
                Messages.getQuestionIcon());

        if (dirName == null || dirName.trim().isEmpty()) {
            return;
        }

        dirName = dirName.trim();

        String newPath = "/".equals(dirPath)
                ? "/" + dirName
                : dirPath + "/" + dirName;

        setState("Creating USS directory " + newPath + "...");

        runBackground(project, () -> {
            try {
                new UssService(
                        ZoweConnectionProvider.current())
                        .createDirectory(newPath);

                SwingUtilities.invokeLater(() -> {
                    setState("Created USS directory " + newPath + ".");
                    listUss(project, dirPath);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setState("Could not create USS directory " + newPath + ": " + ex.getMessage());
                    Messages.showErrorDialog(
                            project,
                            "Could not create USS directory " + newPath + ".\n\n" + ex.getMessage(),
                            "Create USS Directory Failed");
                });
            }
        });
    }

    private void openUssWithEncoding(
            Project project,
            UssNode item) {

        String fullPath = item.fullPath();

        String encoding = Messages.showEditableChooseDialog(
                "Open the file decoding it as this code set (the file tag is not changed).\n"
                        + "Saving from the editor also writes it with this code set.",
                "Open With Encoding: " + item.file.getName(),
                Messages.getQuestionIcon(),
                new String[]{"ISO8859-1", "UTF-8", "IBM-1047", "IBM-037", "IBM-850"},
                "ISO8859-1",
                null);

        if (encoding == null || encoding.trim().isEmpty()) {
            return;
        }

        String selected = encoding.trim();
        setState("Opening USS file " + fullPath + " as " + selected + "...");

        runBackground(project, () -> {
            String content = new UssService(ZoweConnectionProvider.current())
                    .readText(fullPath, selected);

            SwingUtilities.invokeLater(() -> {
                remoteEditors.openUss(fullPath, content, selected);
                setState("Opened " + fullPath + " as " + selected + ".");
            });
        });
    }

    private static final String TAG_BINARY = "binary";
    private static final String TAG_REMOVE = "remove tag (untagged)";

    private void changeUssTag(
            Project project,
            UssNode item) {

        String targetPath = item.fullPath();

        // Editable, so any other code set (for example IBM-037) can be typed in.
        String choice = Messages.showEditableChooseDialog(
                "Select the encoding the file is actually stored in, or type another code set.\n"
                        + "Use ISO8859-1 for ASCII files that show up as garbage when opened.",
                "Change Tag: " + item.file.getName(),
                Messages.getQuestionIcon(),
                new String[]{"ISO8859-1", "UTF-8", "IBM-1047", "IBM-037", TAG_BINARY, TAG_REMOVE},
                "ISO8859-1",
                null);

        if (choice == null || choice.trim().isEmpty()) {
            return;
        }

        String selected = choice.trim();
        setState("Changing tag of " + targetPath + " to " + selected + "...");

        runBackground(project, () -> {
            try {
                UssService service = new UssService(ZoweConnectionProvider.current());
                if (TAG_BINARY.equals(selected)) {
                    service.setBinaryTag(targetPath);
                } else if (TAG_REMOVE.equals(selected)) {
                    service.removeTag(targetPath);
                } else {
                    service.setTextTag(targetPath, selected);
                }

                SwingUtilities.invokeLater(() -> {
                    setState("Tagged " + targetPath + " as " + selected
                            + ". Close and reopen the file if it is already open.");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setState("Could not change tag of " + targetPath + ": " + ex.getMessage());
                    Messages.showErrorDialog(
                            project,
                            "Could not change tag of " + targetPath + ".\n\n" + ex.getMessage(),
                            "Change Tag Failed");
                });
            }
        });
    }

    private void renameUssSelection(
            Project project,
            UssNode item) {

        String oldPath = item.fullPath();
        String oldName = item.file.getName();

        String newName = Messages.showInputDialog(
                project,
                "Enter new name for " + oldName + ":",
                "Rename USS Item",
                Messages.getQuestionIcon(),
                oldName,
                null);

        if (newName == null || newName.trim().isEmpty() || newName.trim().equals(oldName)) {
            return;
        }

        newName = newName.trim();
        String currentDirectory = normalizePath(ussPath.getText());
        String newPath = "/".equals(currentDirectory)
                ? "/" + newName
                : currentDirectory + "/" + newName;

        setState("Renaming " + oldPath + " to " + newPath + "...");

        runBackground(project, () -> {
            try {
                new UssService(
                        ZoweConnectionProvider.current())
                        .rename(oldPath, newPath);

                SwingUtilities.invokeLater(() -> {
                    setState("Renamed " + oldPath + " to " + newPath + ".");
                    listUss(project, currentDirectory);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setState("Could not rename " + oldPath + ": " + ex.getMessage());
                    Messages.showErrorDialog(
                            project,
                            "Could not rename " + oldPath + " to " + newPath + ".\n\n" + ex.getMessage(),
                            "Rename USS Item Failed");
                });
            }
        });
    }

    private void deleteUssSelection(
            Project project,
            UssNode item) {

        String targetPath = item.fullPath();
        boolean isDir = item.isDirectory();
        String itemType = isDir ? "directory" : "file";

        int confirm = Messages.showYesNoDialog(
                project,
                "Are you sure you want to delete " + itemType + " '" + targetPath + "'?\n\nThis action cannot be undone.",
                "Delete USS " + (isDir ? "Directory" : "File"),
                Messages.getWarningIcon());

        if (confirm != Messages.YES) {
            return;
        }

        setState("Deleting USS " + itemType + " " + targetPath + "...");

        runBackground(project, () -> {
            try {
                new UssService(
                        ZoweConnectionProvider.current())
                        .delete(targetPath, isDir);

                SwingUtilities.invokeLater(() -> {
                    setState("Deleted USS " + itemType + " " + targetPath + ".");
                    listUss(project, ussPath.getText());
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setState("Could not delete " + targetPath + ": " + ex.getMessage());
                    Messages.showErrorDialog(
                            project,
                            "Could not delete USS " + itemType + " " + targetPath + ".\n\n" + ex.getMessage(),
                            "Delete USS Item Failed");
                });
            }
        });
    }

    private void showSelectedUssNode() {

        DefaultMutableTreeNode node =
                selectedNode(ussTree);

        if (node != null
                && node.getUserObject()
                instanceof UssNode item) {

            ussDetails.setText(
                    formatUnixFile(item.file));

            ussDetails.setCaretPosition(0);
        }
    }

    // -------------------------------------------------------------------------
    // Commands
    // -------------------------------------------------------------------------

    private JComponent createCommandsPanel(
            Project project) {

        JTabbedPane commandTabs =
                new JTabbedPane();

        commandTabs.addTab(
                "TSO",
                createTsoCommandPanel(project));

        commandTabs.addTab(
                "MVS Console",
                createConsoleCommandPanel(project));

        commandTabs.addTab(
                "USS SSH",
                createSshCommandPanel(project));

        return commandTabs;
    }

    private JComponent createTsoCommandPanel(
            Project project) {

        JPanel panel =
                new JPanel(
                        new BorderLayout(
                                4,
                                4));

        JPanel top =
                new JPanel(
                        new WrapLayout(
                                FlowLayout.LEFT,
                                4,
                                2));

        JButton start =
                new JButton("Start Session");

        JButton stop =
                new JButton("Stop Session");

        JButton clear =
                new JButton("Clear");

        start.addActionListener(
                e -> startTsoSession(project));

        stop.addActionListener(
                e -> stopTsoSession(project));

        clear.addActionListener(
                e -> tsoOutput.setText(""));

        top.add(start);
        top.add(stop);
        top.add(clear);
        top.add(tsoSessionState);

        JPanel entry =
                new JPanel(
                        new BorderLayout(
                                4,
                                2));

        JButton run =
                new JButton("Run");

        tsoInput.setToolTipText(
                "Issue a TSO command using the active reusable TSO session");

        tsoInput.addActionListener(
                e -> issueTso(project));

        run.addActionListener(
                e -> issueTso(project));

        entry.add(
                new JBLabel("TSO> "),
                BorderLayout.WEST);

        entry.add(
                tsoInput,
                BorderLayout.CENTER);

        entry.add(
                run,
                BorderLayout.EAST);

        panel.add(
                top,
                BorderLayout.NORTH);

        panel.add(
                new JBScrollPane(
                        tsoOutput),
                BorderLayout.CENTER);

        panel.add(
                entry,
                BorderLayout.SOUTH);

        return panel;
    }

    private JComponent createConsoleCommandPanel(
            Project project) {

        JPanel panel =
                new JPanel(
                        new BorderLayout(
                                4,
                                4));

        JPanel top =
                new JPanel(
                        new WrapLayout(
                                FlowLayout.LEFT,
                                4,
                                2));

        JButton clear =
                new JButton("Clear");

        clear.addActionListener(
                e -> consoleOutput.setText(""));

        top.add(
                new JBLabel(
                        "Synchronous z/OSMF console commands"));

        top.add(clear);

        JPanel entry =
                new JPanel(
                        new BorderLayout(
                                4,
                                2));

        JButton run =
                new JButton("Run");

        consoleInput.addActionListener(
                e -> issueConsole(project));

        run.addActionListener(
                e -> issueConsole(project));

        entry.add(
                new JBLabel("MVS> "),
                BorderLayout.WEST);

        entry.add(
                consoleInput,
                BorderLayout.CENTER);

        entry.add(
                run,
                BorderLayout.EAST);

        panel.add(
                top,
                BorderLayout.NORTH);

        panel.add(
                new JBScrollPane(
                        consoleOutput),
                BorderLayout.CENTER);

        panel.add(
                entry,
                BorderLayout.SOUTH);

        return panel;
    }

    private JComponent createSshCommandPanel(
            Project project) {

        JPanel panel =
                new JPanel(
                        new BorderLayout(
                                4,
                                4));

        JPanel top =
                new JPanel(
                        new WrapLayout(
                                FlowLayout.LEFT,
                                4,
                                2));

        JButton clear =
                new JButton("Clear");

        clear.addActionListener(
                e -> sshOutput.setText(""));

        top.add(
                new JBLabel(
                        "USS command execution over SSH "
                                + "(known_hosts verification enabled)"));

        top.add(clear);

        JPanel entry =
                new JPanel(
                        new BorderLayout(
                                4,
                                2));

        JButton run =
                new JButton("Run");

        sshInput.addActionListener(
                e -> issueSsh(project));

        run.addActionListener(
                e -> issueSsh(project));

        entry.add(
                new JBLabel("USS$ "),
                BorderLayout.WEST);

        entry.add(
                sshInput,
                BorderLayout.CENTER);

        entry.add(
                run,
                BorderLayout.EAST);

        panel.add(
                top,
                BorderLayout.NORTH);

        panel.add(
                new JBScrollPane(
                        sshOutput),
                BorderLayout.CENTER);

        panel.add(
                entry,
                BorderLayout.SOUTH);

        return panel;
    }

    private CommandService commandService() {

        CommandService service =
                commandService;

        if (service == null) {

            synchronized (this) {

                service =
                        commandService;

                if (service == null) {

                    commandService =
                            service =
                                    ZoweConnectionProvider
                                            .currentCommandService();
                }
            }
        }

        return service;
    }

    private void startTsoSession(
            Project project) {

        setState(
                "Starting reusable TSO session...");

        runBackground(project, () -> {

            String session =
                    commandService()
                            .startTsoSession();

            SwingUtilities.invokeLater(() -> {

                tsoSessionState.setText(
                        "TSO session: "
                                + session);

                appendTerminal(
                        tsoOutput,
                        "[session started: "
                                + session
                                + "]\n");

                setState(
                        "TSO session started: "
                                + session);
            });
        });
    }

    private void stopTsoSession(
            Project project) {

        setState(
                "Stopping TSO session...");

        runBackground(project, () -> {

            commandService()
                    .stopTsoSession();

            SwingUtilities.invokeLater(() -> {

                tsoSessionState.setText(
                        "TSO session: stopped");

                appendTerminal(
                        tsoOutput,
                        "[session stopped]\n");

                setState(
                        "TSO session stopped.");
            });
        });
    }

    private void issueTso(
            Project project) {

        String command =
                tsoInput.getText().trim();

        if (command.isEmpty()) {
            return;
        }

        tsoInput.setText("");

        appendTerminal(
                tsoOutput,
                "TSO> "
                        + command
                        + "\n");

        setState(
                "Running TSO command...");

        runBackground(project, () -> {

            List<String> output =
                    commandService()
                            .issueTso(command);

            String session =
                    commandService()
                            .getTsoSessionId();

            SwingUtilities.invokeLater(() -> {

                tsoSessionState.setText(
                        "TSO session: "
                                + session);

                if (output.isEmpty()) {

                    appendTerminal(
                            tsoOutput,
                            "(no output)\n");

                } else {

                    output.forEach(
                            line ->
                                    appendTerminal(
                                            tsoOutput,
                                            line + "\n"));
                }

                appendTerminal(
                        tsoOutput,
                        "\n");

                setState(
                        "TSO command completed using session "
                                + session
                                + ".");
            });
        });
    }

    private void issueConsole(
            Project project) {

        String command =
                consoleInput
                        .getText()
                        .trim();

        if (command.isEmpty()) {
            return;
        }

        consoleInput.setText("");

        appendTerminal(
                consoleOutput,
                "MVS> "
                        + command
                        + "\n");

        setState(
                "Running MVS console command...");

        runBackground(project, () -> {

            String output =
                    commandService()
                            .issueConsole(command);

            SwingUtilities.invokeLater(() -> {

                appendTerminal(
                        consoleOutput,
                        output + "\n\n");

                setState(
                        "MVS console command completed.");
            });
        });
    }

    private void issueSsh(
            Project project) {

        String command =
                sshInput
                        .getText()
                        .trim();

        if (command.isEmpty()) {
            return;
        }

        sshInput.setText("");

        String cwd =
                commandService()
                        .getUssCwd();

        appendTerminal(
                sshOutput,
                "USS"
                        + (cwd == null ? "" : " " + cwd)
                        + "$ "
                        + command
                        + "\n");

        setState(
                "Running USS SSH command...");

        runBackground(project, () -> {

            String output =
                    commandService()
                            .issueUss(command);

            SwingUtilities.invokeLater(() -> {

                appendTerminal(
                        sshOutput,
                        output
                                + (output.endsWith("\n")
                                ? "\n"
                                : "\n\n"));

                setState(
                        "USS SSH command completed.");
            });
        });
    }

    private static void appendTerminal(
            JBTextArea area,
            String text) {

        area.append(text);

        area.setCaretPosition(
                area.getDocument()
                        .getLength());
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private Job selectedJob(
            Project project) {

        DefaultMutableTreeNode node =
                selectedNode(jobsTree);

        if (node != null) {

            Object value =
                    node.getUserObject();

            if (value
                    instanceof JobNode j) {

                return j.job;
            }

            if (value
                    instanceof SpoolNode s) {

                return s.job;
            }
        }

        Messages.showInfoMessage(
                project,
                "Select a job first.",
                "z/OS Workbench for Zowe");

        return null;
    }

    private DefaultMutableTreeNode selectedNode(
            JTree tree) {

        TreePath path =
                tree.getSelectionPath();

        return path == null
                ? null
                : (DefaultMutableTreeNode)
                path.getLastPathComponent();
    }

    private DefaultMutableTreeNode findJobNode(
            Job job) {

        for (int i = 0;
             i < jobsRoot.getChildCount();
             i++) {

            DefaultMutableTreeNode node =
                    (DefaultMutableTreeNode)
                            jobsRoot.getChildAt(i);

            Object value =
                    node.getUserObject();

            if (value
                    instanceof JobNode j
                    && j.job.getJobName()
                    .equals(job.getJobName())
                    && j.job.getJobId()
                    .equals(job.getJobId())) {

                return node;
            }
        }

        return null;
    }

    private void runBackground(
            Project project,
            ThrowingRunnable task) {

        ApplicationManager
                .getApplication()
                .executeOnPooledThread(() -> {

                    try {

                        task.run();

                    } catch (Throwable ex) {

                        showError(
                                project,
                                ex);
                    }
                });
    }

    private void showError(
            Project project,
            Throwable ex) {

        SwingUtilities.invokeLater(() -> {

            setState(
                    "Error: "
                            + ex.getMessage());

            Messages.showErrorDialog(
                    project,
                    formatErrorMessage(ex),
                    "z/OS Workbench for Zowe");
        });
    }

    private static String formatErrorMessage(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        sb.append(ex.getMessage() != null ? ex.getMessage() : ex.toString());

        if (ex instanceof ZosmfRequestException zex && zex.getResponse() != null) {
            zex.getResponse().getResponsePhraseAsString().ifPresent(phrase -> {
                if (!phrase.isBlank()) {
                    sb.append("\n\nResponse details: ").append(phrase);
                }
            });
        }

        Throwable cause = ex.getCause();
        while (cause != null && cause != ex) {
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                sb.append("\n\nCaused by: ").append(cause.getMessage());
            }
            cause = cause.getCause();
        }
        return sb.toString();
    }

    private void setState(
            String text) {

        globalState.setText(text);
    }

    private static JBTextArea textArea(
            boolean editable) {

        JBTextArea area =
                new JBTextArea();

        area.setEditable(editable);

        area.setLineWrap(true);
        area.setWrapStyleWord(true);

        area.setFont(
                new Font(
                        Font.MONOSPACED,
                        Font.PLAIN,
                        area.getFont()
                                .getSize()));

        return area;
    }

    private String formatJob(
            Job job) {

        return "Job Name : "
                + job.getJobName()
                + "\n"
                + "Job ID   : "
                + job.getJobId()
                + "\n"
                + "Owner    : "
                + job.getOwner()
                + "\n"
                + "Status   : "
                + job.getStatus()
                + "\n"
                + "Retcode  : "
                + job.getRetCode()
                + "\n";
    }

    private String formatJobFile(JobFile file) {
        if (file == null) {
            return "";
        }
        String str = file.toString();
        if (str.startsWith("JobFile{") && str.endsWith("}")) {
            str = str.substring(8, str.length() - 1);
        } else if (str.contains("{") && str.endsWith("}")) {
            str = str.substring(str.indexOf('{') + 1, str.length() - 1);
        }

        StringBuilder sb = new StringBuilder();
        java.util.List<String> pairs = parseKeyValuePairs(str);
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = pair.substring(0, eq).trim();
                String val = pair.substring(eq + 1).trim();
                if (val.startsWith("'") && val.endsWith("'") && val.length() >= 2) {
                    val = val.substring(1, val.length() - 1);
                }
                String label = formatJobFileKeyLabel(key);
                sb.append(String.format("%-15s: %s\n", label, val));
            } else if (!pair.isBlank()) {
                sb.append(pair).append("\n");
            }
        }
        return sb.toString();
    }

    private static String formatJobFileKeyLabel(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "ddname" -> "DD Name";
            case "jobid" -> "Job ID";
            case "jobname" -> "Job Name";
            case "stepname" -> "Step Name";
            case "procstep" -> "Proc Step";
            case "subsystem" -> "Sub System";
            case "id" -> "ID";
            case "recfm" -> "RECFM";
            case "lrecl" -> "LRECL";
            case "bytecount" -> "Byte Count";
            case "recordcount" -> "Record Count";
            case "classs" -> "Class";
            case "jobcorrelator" -> "Job Correlator";
            case "recordsurl" -> "Records URL";
            default -> key.isEmpty() ? key : Character.toUpperCase(key.charAt(0)) + key.substring(1);
        };
    }

    private String formatUnixFile(UnixFile file) {
        if (file == null) {
            return "";
        }
        String str = file.toString();
        if (str.startsWith("UnixFile{") && str.endsWith("}")) {
            str = str.substring(9, str.length() - 1);
        } else if (str.contains("{") && str.endsWith("}")) {
            str = str.substring(str.indexOf('{') + 1, str.length() - 1);
        }

        StringBuilder sb = new StringBuilder();
        java.util.List<String> pairs = parseKeyValuePairs(str);
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = pair.substring(0, eq).trim();
                String val = pair.substring(eq + 1).trim();
                if (val.startsWith("'") && val.endsWith("'") && val.length() >= 2) {
                    val = val.substring(1, val.length() - 1);
                }
                String label = formatUnixFileKeyLabel(key);
                sb.append(String.format("%-10s: %s\n", label, val));
            } else if (!pair.isBlank()) {
                sb.append(pair).append("\n");
            }
        }
        return sb.toString();
    }

    private static String formatUnixFileKeyLabel(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "name" -> "Name";
            case "mode" -> "Mode";
            case "size" -> "Size";
            case "uid" -> "User ID";
            case "user" -> "User";
            case "gid" -> "Group ID";
            case "group" -> "Group";
            case "mtime" -> "Modified";
            case "target" -> "Target";
            default -> key.isEmpty() ? key : Character.toUpperCase(key.charAt(0)) + key.substring(1);
        };
    }

    private static boolean isPds(Dataset dataset) {
        if (dataset == null) {
            return false;
        }
        String dsorg = dataset.getDsorg();
        if (dsorg != null && !dsorg.isBlank()) {
            String upper = dsorg.trim().toUpperCase(Locale.ROOT);
            return upper.startsWith("PO") || upper.contains("PO");
        }
        return false;
    }

    private static boolean isSequential(Dataset dataset) {
        if (dataset == null) {
            return false;
        }
        String dsorg = dataset.getDsorg();
        if (dsorg != null && !dsorg.isBlank()) {
            String upper = dsorg.trim().toUpperCase(Locale.ROOT);
            return upper.startsWith("PS") || upper.contains("PS");
        }
        return false;
    }

    private static boolean isArchivedDataset(Dataset dataset) {
        if (dataset == null) {
            return false;
        }
        String vol = dataset.getVol();
        if (vol != null && !vol.isBlank()) {
            String upper = vol.trim().toUpperCase(Locale.ROOT);
            return upper.contains("ARCIVE") || upper.contains("MIGRAT");
        }
        return false;
    }

    private static boolean isArchivedError(Throwable ex) {
        if (ex == null) {
            return false;
        }
        String text = ex.getMessage();
        if (text == null) {
            text = ex.toString();
        } else {
            text = text + " " + ex.toString();
        }
        if (ex.getCause() != null) {
            text += " " + ex.getCause().toString();
        }
        String upper = text.toUpperCase(Locale.ROOT);
        return upper.contains("ARCIVE")
                || upper.contains("ARCHIV")
                || upper.contains("MIGRAT")
                || upper.contains("CA DISK")
                || upper.contains("AUTO RESTORE")
                || upper.contains("TSO PROMPT")
                || upper.contains("WAIT FOR THE RESTORE");
    }

    private void showArchivedMessage(Project project, String target) {
        String message = "Data set " + target + " cannot be selected or opened because it is archived on z/OS.\n\n"
                + "The data set is archived/migrated (e.g. via CA Disk or DFHSM to tape/secondary storage)\n"
                + "and requires restoration before it can be accessed.";
        setState("Data set " + target + " cannot be accessed because it is archived on z/OS.");
        Messages.showWarningDialog(project, message, "Data Set Archived");
    }

    private String formatDataset(
            Dataset d) {

        StringBuilder sb = new StringBuilder();
        sb.append("Data Set : ").append(d.getDsname() != null ? d.getDsname() : "").append("\n")
                .append("DSORG    : ").append(d.getDsorg() != null ? d.getDsorg() : "").append("\n")
                .append("RECFM    : ").append(d.getRecfm() != null ? d.getRecfm() : "").append("\n")
                .append("LRECL    : ").append(d.getLrectl() != null ? d.getLrectl() : "").append("\n")
                .append("BLKSIZE  : ").append(d.getBlksz() != null ? d.getBlksz() : "").append("\n")
                .append("Volume   : ").append(d.getVol() != null ? d.getVol() : "").append("\n")
                .append("Created  : ").append(d.getCdate() != null ? d.getCdate() : "").append("\n")
                .append("Used     : ").append(d.getUsed() != null ? d.getUsed() : "").append("\n");

        if (isPds(d)) {
            sb.append("Type     : Partitioned Data Set (PDS/PDSE) - Double-click to list members\n");
        } else if (isSequential(d)) {
            sb.append("Type     : Sequential Data Set (PS) - Double-click to open file\n");
        } else if (d.getDsorg() != null && !d.getDsorg().isBlank()) {
            sb.append("Type     : Non-sequential and non-partitioned data set (").append(d.getDsorg()).append(")\n");
        } else {
            sb.append("Type     : \n");
        }

        if (isArchivedDataset(d)) {
            sb.append("Status   : Archived / Migrated on z/OS\n");
        }
        return sb.toString();
    }

    private String formatMember(Member member) {
        if (member == null) {
            return "";
        }
        String str = member.toString();
        if (str.startsWith("Member{") && str.endsWith("}")) {
            str = str.substring(7, str.length() - 1);
        } else if (str.contains("{") && str.endsWith("}")) {
            str = str.substring(str.indexOf('{') + 1, str.length() - 1);
        }

        StringBuilder sb = new StringBuilder();
        java.util.List<String> pairs = parseKeyValuePairs(str);
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = pair.substring(0, eq).trim();
                String val = pair.substring(eq + 1).trim();
                if (val.startsWith("'") && val.endsWith("'") && val.length() >= 2) {
                    val = val.substring(1, val.length() - 1);
                }
                String label = formatKeyLabel(key);
                sb.append(String.format("%-10s: %s\n", label, val));
            } else if (!pair.isBlank()) {
                sb.append(pair).append("\n");
            }
        }
        return sb.toString();
    }

    private static String formatKeyLabel(String key) {
        return switch (key.toLowerCase()) {
            case "member" -> "Member";
            case "vers" -> "Version";
            case "mod" -> "Mod Level";
            case "c4date" -> "Created";
            case "m4date" -> "Modified";
            case "cnorc" -> "Current";
            case "inorc" -> "Initial";
            case "mnorc" -> "Mod Lines";
            case "mtime" -> "Mod Time";
            case "msec" -> "Mod Sec";
            case "user" -> "User ID";
            case "sclm" -> "SCLM";
            default -> key.isEmpty() ? key : Character.toUpperCase(key.charAt(0)) + key.substring(1);
        };
    }

    private static java.util.List<String> parseKeyValuePairs(String input) {
        java.util.List<String> list = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\'') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == ',' && !inQuotes) {
                list.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            list.add(current.toString().trim());
        }
        return list;
    }

    private static String normalizePath(
            String path) {

        if (path == null
                || path.isBlank()) {

            return "/";
        }

        String p =
                path.trim();

        if (!p.startsWith("/")) {

            p =
                    "/" + p;
        }

        while (p.length() > 1
                && p.endsWith("/")) {

            p =
                    p.substring(
                            0,
                            p.length() - 1);
        }

        return p;
    }

    private static String parentPath(
            String path) {

        String p =
                normalizePath(path);

        if ("/".equals(p)) {
            return "/";
        }

        int slash =
                p.lastIndexOf('/');

        return slash <= 0
                ? "/"
                : p.substring(
                0,
                slash);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {

        void run() throws Throwable;
    }

    // -------------------------------------------------------------------------
    // Tree node models
    // -------------------------------------------------------------------------

    private static final class JobNode {

        private final Job job;

        private JobNode(
                Job job) {

            this.job =
                    job;
        }

        @Override
        public String toString() {

            return job.getJobName()
                    + " / "
                    + job.getJobId()
                    + "  ["
                    + job.getStatus()
                    + "]";
        }
    }

    private static final class SpoolNode {

        private final Job job;
        private final JobFile file;

        private SpoolNode(
                Job job,
                JobFile file) {

            this.job =
                    job;

            this.file =
                    file;
        }

        @Override
        public String toString() {

            String ddName = file != null ? file.getDdName() : null;
            if (ddName != null && !ddName.isBlank()) {
                return ddName;
            }
            return "SPOOL";
        }
    }

    private static final class DatasetNode {

        private final Dataset dataset;

        private DatasetNode(
                Dataset dataset) {

            this.dataset =
                    dataset;
        }

        @Override
        public String toString() {

            return dataset.getDsname();
        }
    }

    private static final class MemberNode {

        private final String dataSetName;
        private final Member member;

        private MemberNode(
                String dataSetName,
                Member member) {

            this.dataSetName =
                    dataSetName;

            this.member =
                    member;
        }

        private String qualifiedName() {

            return dataSetName
                    + "("
                    + member.getMember()
                    + ")";
        }

        @Override
        public String toString() {

            return member.getMember();
        }
    }

    private static final class UssNode {

        private final String parent;
        private final UnixFile file;

        private UssNode(
                String parent,
                UnixFile file) {

            this.parent =
                    parent;

            this.file =
                    file;
        }

        private boolean isDirectory() {

            return file.getMode() != null
                    && file.getMode()
                    .startsWith("d");
        }

        private String fullPath() {

            if (file.getName()
                    .startsWith("/")) {

                return file.getName();
            }

            return "/".equals(parent)
                    ? "/"
                    + file.getName()
                    : parent
                    + "/"
                    + file.getName();
        }

        @Override
        public String toString() {

            String suffix =
                    isDirectory()
                            ? "/"
                            : "";

            return file.getName()
                    + suffix
                    + "  ["
                    + file.getMode()
                    + ", "
                    + file.getSize()
                    + " bytes]";
        }
    }

    // -------------------------------------------------------------------------
    // Symbols & Variables Tab
    // -------------------------------------------------------------------------

    private JComponent createSymbolsPanel(Project project) {
        JPanel panel = new JPanel(new BorderLayout(6, 6));

        JPanel toolbar = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 4));

        toolbar.add(new JBLabel("Scope:"));
        toolbar.add(symbolSystemCombo);
        toolbar.add(new JBLabel("Type:"));
        toolbar.add(symbolTypeCombo);
        toolbar.add(new JBLabel("Filter:"));
        symbolFilterField.setPreferredSize(new Dimension(140, 26));
        toolbar.add(symbolFilterField);

        JButton refreshBtn = new JButton("Refresh", AllIcons.Actions.Refresh);
        refreshBtn.addActionListener(e -> refreshSymbols(project));

        symbolAddButton.addActionListener(e -> addVariable(project));
        symbolEditButton.addActionListener(e -> editSelectedVariable(project));
        symbolDeleteButton.addActionListener(e -> deleteSelectedVariables(project));
        symbolRenameButton.addActionListener(e -> renameSelectedVariable(project));

        toolbar.add(refreshBtn);
        toolbar.add(symbolAddButton);
        toolbar.add(symbolEditButton);
        toolbar.add(symbolDeleteButton);
        toolbar.add(symbolRenameButton);

        symbolTypeCombo.addActionListener(e -> {
            boolean isVariable = symbolTypeCombo.getSelectedIndex() == 1;
            symbolAddButton.setEnabled(isVariable);
            symbolEditButton.setEnabled(isVariable);
            symbolDeleteButton.setEnabled(isVariable);
            symbolRenameButton.setEnabled(isVariable);
            refreshSymbols(project);
        });

        symbolSystemCombo.addActionListener(e -> {
            if (!isUpdatingSymbolCombo) {
                refreshSymbols(project);
            }
        });

        symbolFilterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applySymbolsFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applySymbolsFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applySymbolsFilter();
            }
        });

        symbolsTable.setAutoCreateRowSorter(true);
        symbolsTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        JPopupMenu tableMenu = new JPopupMenu();
        JMenuItem editItem = new JMenuItem("Edit Value...", AllIcons.Actions.Edit);
        JMenuItem renameItem = new JMenuItem("Rename...", AllIcons.Actions.Replace);
        JMenuItem deleteItem = new JMenuItem("Delete", AllIcons.Actions.GC);
        JMenuItem copyNameItem = new JMenuItem("Copy Name", AllIcons.Actions.Copy);
        JMenuItem copyValueItem = new JMenuItem("Copy Value", AllIcons.Actions.Copy);

        editItem.addActionListener(e -> editSelectedVariable(project));
        renameItem.addActionListener(e -> renameSelectedVariable(project));
        deleteItem.addActionListener(e -> deleteSelectedVariables(project));
        copyNameItem.addActionListener(e -> copySelectedTableCell(0));
        copyValueItem.addActionListener(e -> copySelectedTableCell(1));

        tableMenu.add(editItem);
        tableMenu.add(renameItem);
        tableMenu.add(deleteItem);
        tableMenu.addSeparator();
        tableMenu.add(copyNameItem);
        tableMenu.add(copyValueItem);

        symbolsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    if (symbolTypeCombo.getSelectedIndex() == 1) {
                        editSelectedVariable(project);
                    }
                }
            }

            private void maybeShowPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = symbolsTable.rowAtPoint(e.getPoint());
                    if (row != -1 && !symbolsTable.isRowSelected(row)) {
                        symbolsTable.setRowSelectionInterval(row, row);
                    }
                    boolean isVar = symbolTypeCombo.getSelectedIndex() == 1;
                    editItem.setEnabled(isVar);
                    renameItem.setEnabled(isVar);
                    deleteItem.setEnabled(isVar);
                    tableMenu.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });

        // Default to System Symbols
        symbolAddButton.setEnabled(false);
        symbolEditButton.setEnabled(false);
        symbolDeleteButton.setEnabled(false);
        symbolRenameButton.setEnabled(false);

        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(new JBScrollPane(symbolsTable), BorderLayout.CENTER);
        panel.add(symbolsStatusLabel, BorderLayout.SOUTH);

        return panel;
    }

    private void refreshSystemsList(Project project) {
        runBackground(project, () -> {
            List<DefinedSystem> systems = new SymbolService(ZoweConnectionProvider.current()).listDefinedSystems();
            SwingUtilities.invokeLater(() -> {
                definedSystemsList = systems;
                isUpdatingSymbolCombo = true;
                try {
                    String prevSelected = (String) symbolSystemCombo.getSelectedItem();
                    symbolSystemCombo.removeAllItems();
                    symbolSystemCombo.addItem("Local System");
                    for (DefinedSystem sys : systems) {
                        String sysName = sys.getSystemName();
                        String plexName = sys.getSysplexName();
                        String label = sysName != null ? sysName : "";
                        if (plexName != null && !plexName.isBlank()) {
                            label += " (" + plexName + ")";
                        }
                        symbolSystemCombo.addItem(label);
                    }
                    if (prevSelected != null) {
                        symbolSystemCombo.setSelectedItem(prevSelected);
                    }
                } finally {
                    isUpdatingSymbolCombo = false;
                }
                refreshSymbols(project);
            });
        });
    }

    private String[] getSelectedSysplexAndSystem() {
        int index = symbolSystemCombo.getSelectedIndex();
        if (index <= 0 || definedSystemsList == null || index - 1 >= definedSystemsList.size()) {
            return new String[]{"", ""};
        }
        DefinedSystem sys = definedSystemsList.get(index - 1);
        String plex = sys.getSysplexName() != null ? sys.getSysplexName() : "";
        String system = sys.getSystemName() != null ? sys.getSystemName() : "";
        return new String[]{plex, system};
    }

    private void refreshSymbols(Project project) {
        String[] scope = getSelectedSysplexAndSystem();
        String sysplexName = scope[0];
        String systemName = scope[1];
        boolean isSymbol = symbolTypeCombo.getSelectedIndex() == 0;

        String typeLabel = isSymbol ? "symbols" : "variables";
        symbolsStatusLabel.setText("Loading z/OS " + typeLabel + "...");

        runBackground(project, () -> {
            List<VariableResponse> list = new SymbolService(ZoweConnectionProvider.current())
                    .getSymbolsOrVariables(sysplexName, systemName, isSymbol);

            SwingUtilities.invokeLater(() -> {
                rawSymbolsList = list != null ? list : Collections.emptyList();
                applySymbolsFilter();
                String targetName = systemName.isBlank() ? "Local System" : systemName;
                symbolsStatusLabel.setText("Loaded " + rawSymbolsList.size() + " " + typeLabel + " for " + targetName);
            });
        });
    }

    private void applySymbolsFilter() {
        String filter = symbolFilterField.getText().trim().toLowerCase(Locale.ROOT);
        symbolsTableModel.setRowCount(0);

        for (VariableResponse var : rawSymbolsList) {
            String name = var.getName() != null ? var.getName() : "";
            String value = var.getValue() != null ? var.getValue() : "";
            String desc = var.getDescription() != null ? var.getDescription() : "";

            if (filter.isBlank()
                    || name.toLowerCase(Locale.ROOT).contains(filter)
                    || value.toLowerCase(Locale.ROOT).contains(filter)
                    || desc.toLowerCase(Locale.ROOT).contains(filter)) {
                symbolsTableModel.addRow(new Object[]{name, value, desc});
            }
        }
    }

    private void addVariable(Project project) {
        String[] scope = getSelectedSysplexAndSystem();
        String sysplex = scope[0];
        String system = scope[1];

        JPanel dialogPanel = new JPanel(new GridLayout(5, 2, 5, 5));
        JBTextField nameField = new JBTextField();
        JBTextField valueField = new JBTextField();
        JBTextField descField = new JBTextField();
        JBTextField sysplexField = new JBTextField(sysplex);
        JBTextField systemField = new JBTextField(system);

        dialogPanel.add(new JBLabel("Sysplex Name:"));
        dialogPanel.add(sysplexField);
        dialogPanel.add(new JBLabel("System Name:"));
        dialogPanel.add(systemField);
        dialogPanel.add(new JBLabel("Variable Name:"));
        dialogPanel.add(nameField);
        dialogPanel.add(new JBLabel("Value:"));
        dialogPanel.add(valueField);
        dialogPanel.add(new JBLabel("Description:"));
        dialogPanel.add(descField);

        int option = JOptionPane.showConfirmDialog(
                null,
                dialogPanel,
                "Add z/OSMF System Variable",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);

        if (option != JOptionPane.OK_OPTION) {
            return;
        }

        String varName = nameField.getText().trim();
        String varValue = valueField.getText().trim();
        String varDesc = descField.getText().trim();
        String targetPlex = sysplexField.getText().trim();
        String targetSys = systemField.getText().trim();

        if (varName.isBlank()) {
            Messages.showErrorDialog(project, "Variable Name cannot be empty.", "Add Variable");
            return;
        }

        setState("Creating variable " + varName + "...");

        runBackground(project, () -> {
            new SymbolService(ZoweConnectionProvider.current())
                    .createOrUpdateVariable(targetPlex, targetSys, varName, varValue, varDesc);

            SwingUtilities.invokeLater(() -> {
                setState("Created variable " + varName + ".");
                refreshSymbols(project);
            });
        });
    }

    private void editSelectedVariable(Project project) {
        int selectedRow = symbolsTable.getSelectedRow();
        if (selectedRow == -1) {
            Messages.showInfoMessage(project, "Select a variable to edit first.", "Edit Variable");
            return;
        }
        if (symbolTypeCombo.getSelectedIndex() == 0) {
            Messages.showInfoMessage(project, "System Symbols are read-only parmlib symbols.", "Edit Symbol");
            return;
        }

        int modelRow = symbolsTable.convertRowIndexToModel(selectedRow);
        String name = (String) symbolsTableModel.getValueAt(modelRow, 0);
        String currentValue = (String) symbolsTableModel.getValueAt(modelRow, 1);
        String currentDesc = (String) symbolsTableModel.getValueAt(modelRow, 2);

        String[] scope = getSelectedSysplexAndSystem();

        JPanel dialogPanel = new JPanel(new GridLayout(5, 2, 5, 5));
        JBLabel nameLabel = new JBLabel(name);
        JBTextField valueField = new JBTextField(currentValue);
        JBTextField descField = new JBTextField(currentDesc);
        JBTextField sysplexField = new JBTextField(scope[0]);
        JBTextField systemField = new JBTextField(scope[1]);

        dialogPanel.add(new JBLabel("Sysplex Name:"));
        dialogPanel.add(sysplexField);
        dialogPanel.add(new JBLabel("System Name:"));
        dialogPanel.add(systemField);
        dialogPanel.add(new JBLabel("Variable Name:"));
        dialogPanel.add(nameLabel);
        dialogPanel.add(new JBLabel("Value:"));
        dialogPanel.add(valueField);
        dialogPanel.add(new JBLabel("Description:"));
        dialogPanel.add(descField);

        int option = JOptionPane.showConfirmDialog(
                null,
                dialogPanel,
                "Edit z/OSMF Variable: " + name,
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);

        if (option != JOptionPane.OK_OPTION) {
            return;
        }

        String newValue = valueField.getText().trim();
        String newDesc = descField.getText().trim();
        String targetPlex = sysplexField.getText().trim();
        String targetSys = systemField.getText().trim();

        setState("Updating variable " + name + "...");

        runBackground(project, () -> {
            new SymbolService(ZoweConnectionProvider.current())
                    .createOrUpdateVariable(targetPlex, targetSys, name, newValue, newDesc);

            SwingUtilities.invokeLater(() -> {
                setState("Updated variable " + name + ".");
                refreshSymbols(project);
            });
        });
    }

    private void renameSelectedVariable(Project project) {
        int selectedRow = symbolsTable.getSelectedRow();
        if (selectedRow == -1) {
            Messages.showInfoMessage(project, "Select a variable to rename first.", "Rename Variable");
            return;
        }
        if (symbolTypeCombo.getSelectedIndex() == 0) {
            Messages.showInfoMessage(project, "System Symbols are read-only parmlib symbols.", "Rename Symbol");
            return;
        }

        int modelRow = symbolsTable.convertRowIndexToModel(selectedRow);
        String oldName = (String) symbolsTableModel.getValueAt(modelRow, 0);
        String currentValue = (String) symbolsTableModel.getValueAt(modelRow, 1);
        String currentDesc = (String) symbolsTableModel.getValueAt(modelRow, 2);

        String newName = Messages.showInputDialog(
                project,
                "Enter new variable name for '" + oldName + "':",
                "Rename Variable",
                Messages.getQuestionIcon(),
                oldName,
                null);

        if (newName == null || newName.isBlank() || newName.equals(oldName)) {
            return;
        }

        String[] scope = getSelectedSysplexAndSystem();

        setState("Renaming variable " + oldName + " to " + newName + "...");

        runBackground(project, () -> {
            SymbolService service = new SymbolService(ZoweConnectionProvider.current());
            service.createOrUpdateVariable(scope[0], scope[1], newName, currentValue, currentDesc);
            service.deleteVariables(scope[0], scope[1], List.of(oldName));

            SwingUtilities.invokeLater(() -> {
                setState("Renamed variable " + oldName + " to " + newName + ".");
                refreshSymbols(project);
            });
        });
    }

    private void deleteSelectedVariables(Project project) {
        int[] selectedRows = symbolsTable.getSelectedRows();
        if (selectedRows.length == 0) {
            Messages.showInfoMessage(project, "Select at least one variable to delete.", "Delete Variable");
            return;
        }
        if (symbolTypeCombo.getSelectedIndex() == 0) {
            Messages.showInfoMessage(project, "System Symbols are read-only parmlib symbols.", "Delete Symbol");
            return;
        }

        List<String> namesToDelete = new ArrayList<>();
        for (int r : selectedRows) {
            int modelRow = symbolsTable.convertRowIndexToModel(r);
            namesToDelete.add((String) symbolsTableModel.getValueAt(modelRow, 0));
        }

        int confirm = Messages.showYesNoDialog(
                project,
                "Are you sure you want to delete variable(s): " + String.join(", ", namesToDelete) + "?",
                "Delete Variables",
                "Delete",
                "Cancel",
                Messages.getQuestionIcon());

        if (confirm != Messages.YES) {
            return;
        }

        String[] scope = getSelectedSysplexAndSystem();

        setState("Deleting variable(s)...");

        runBackground(project, () -> {
            new SymbolService(ZoweConnectionProvider.current())
                    .deleteVariables(scope[0], scope[1], namesToDelete);

            SwingUtilities.invokeLater(() -> {
                setState("Deleted " + namesToDelete.size() + " variable(s).");
                refreshSymbols(project);
            });
        });
    }

    private void copySelectedTableCell(int col) {
        int selectedRow = symbolsTable.getSelectedRow();
        if (selectedRow != -1) {
            int modelRow = symbolsTable.convertRowIndexToModel(selectedRow);
            String val = (String) symbolsTableModel.getValueAt(modelRow, col);
            if (val != null) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(val), null);
            }
        }
    }
}
