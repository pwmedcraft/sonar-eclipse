/*
 * Sonar, open source software quality management tool.
 * Copyright (C) 2010 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * Sonar is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Sonar is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Sonar; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */

package org.sonar.ide.eclipse.wizards;

import org.apache.commons.lang.StringUtils;
import org.eclipse.core.databinding.DataBindingContext;
import org.eclipse.core.databinding.beans.BeanProperties;
import org.eclipse.core.databinding.observable.list.WritableList;
import org.eclipse.core.databinding.property.value.IValueProperty;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.databinding.viewers.ViewerSupport;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.viewers.*;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.sonar.ide.eclipse.Messages;
import org.sonar.ide.eclipse.SonarImages;
import org.sonar.ide.eclipse.SonarPlugin;
import org.sonar.ide.eclipse.actions.ToggleNatureAction;
import org.sonar.ide.eclipse.core.SonarLogger;
import org.sonar.ide.eclipse.properties.ProjectProperties;
import org.sonar.ide.eclipse.ui.AbstractModelObject;
import org.sonar.ide.eclipse.ui.InlineEditingSupport;
import org.sonar.ide.eclipse.utils.SelectionUtils;
import org.sonar.wsclient.Host;
import org.sonar.wsclient.Sonar;
import org.sonar.wsclient.services.Resource;
import org.sonar.wsclient.services.ResourceQuery;

import com.google.common.collect.Lists;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

/**
 * Inspired by org.eclipse.pde.internal.ui.wizards.tools.ConvertedProjectWizard
 */
public class ConfigureProjectsWizard extends Wizard {

  private ConfigureProjectsPage mainPage;
  private List<IProject> projects;
  private List<IProject> selected;

  public ConfigureProjectsWizard(List<IProject> projects, List<IProject> initialSelection) {
    setNeedsProgressMonitor(true);
    setWindowTitle("Associate with Sonar");
    this.projects = projects;
    this.selected = initialSelection;
  }

  @Override
  public void addPages() {
    mainPage = new ConfigureProjectsPage(projects, selected);
    addPage(mainPage);
  }

  @Override
  public boolean performFinish() {
    return mainPage.finish();
  }

  // TODO move to top level
  public class ConfigureProjectsPage extends WizardPage {
    private List<IProject> projects;
    private List<IProject> selected;
    private CheckboxTableViewer viewer;
    private ComboViewer comboViewer;

    public ConfigureProjectsPage(List<IProject> projects, List<IProject> selected) {
      super("configureProjects", "Associate with Sonar", SonarImages.getImageDescriptor(SonarImages.IMG_SONARWIZBAN));
      setDescription("Select projects to add Sonar capability.");
      this.projects = projects;
      this.selected = selected;
    }

    public void createControl(Composite parent) {
      Composite container = new Composite(parent, SWT.NONE);

      GridLayout layout = new GridLayout();
      layout.numColumns = 1;
      layout.marginHeight = 0;
      layout.marginWidth = 5;
      container.setLayout(layout);

      GridData gridData;

      // List of Sonar servers
      comboViewer = new ComboViewer(container);
      gridData = new GridData(GridData.FILL, GridData.FILL, true, false, 1, 1);
      comboViewer.getCombo().setLayoutData(gridData);
      comboViewer.setContentProvider(ArrayContentProvider.getInstance());
      comboViewer.setLabelProvider(new LabelProvider() {
        @Override
        public String getText(Object element) {
          return ((Host) element).getHost();
        }
      });
      comboViewer.setInput(SonarPlugin.getServerManager().getServers());
      comboViewer.getCombo().select(0);

      // List of projects
      viewer = CheckboxTableViewer.newCheckList(container, SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION);
      gridData = new GridData(GridData.FILL, GridData.FILL, true, true, 1, 1);
      viewer.getTable().setLayoutData(gridData);

      DataBindingContext dbc = new DataBindingContext();

      viewer.getTable().setHeaderVisible(true);

      TableViewerColumn columnProject = new TableViewerColumn(viewer, SWT.LEFT);
      columnProject.getColumn().setText("Project");
      columnProject.getColumn().setWidth(200);

      TableViewerColumn columnGroupId = new TableViewerColumn(viewer, SWT.LEFT);
      columnGroupId.getColumn().setText("GroupId");
      columnGroupId.getColumn().setWidth(200);

      TableViewerColumn columnArtifactId = new TableViewerColumn(viewer, SWT.LEFT);
      columnArtifactId.getColumn().setText("ArtifactId");
      columnArtifactId.getColumn().setWidth(200);

      TableViewerColumn columnBranch = new TableViewerColumn(viewer, SWT.LEFT);
      columnBranch.getColumn().setText("Branch");
      columnBranch.getColumn().setWidth(200);

      columnGroupId.setEditingSupport(new InlineEditingSupport(viewer, dbc, SonarProject.PROPERTY_GROUP_ID));
      columnArtifactId.setEditingSupport(new InlineEditingSupport(viewer, dbc, SonarProject.PROPERTY_ARTIFACT_ID));
      columnBranch.setEditingSupport(new InlineEditingSupport(viewer, dbc, SonarProject.PROPERTY_BRANCH));

      List<SonarProject> list = Lists.newArrayList();
      List<SonarProject> selectedList = Lists.newArrayList();
      for (IProject project : projects) {
        SonarProject sonarProject = new SonarProject(project);
        list.add(sonarProject);
        if (selected.contains(project)) {
          selectedList.add(sonarProject);
        }
      }

      // TODO we can improve UI of table by adding image to first element :
      // PlatformUI.getWorkbench().getSharedImages().getImage(IDE.SharedImages.IMG_OBJ_PROJECT);
      ViewerSupport.bind(viewer,
        new WritableList(list, SonarProject.class),
        new IValueProperty[] {
        BeanProperties.value(SonarProject.class, SonarProject.PROPERTY_PROJECT_NAME),
        BeanProperties.value(SonarProject.class, SonarProject.PROPERTY_GROUP_ID),
        BeanProperties.value(SonarProject.class, SonarProject.PROPERTY_ARTIFACT_ID),
        BeanProperties.value(SonarProject.class, SonarProject.PROPERTY_BRANCH)
       });
      viewer.setCheckedElements(selectedList.toArray(new SonarProject[selectedList.size()]));

      Button autoConfigButton = new Button(container, SWT.PUSH);
      autoConfigButton.setText(Messages.getString("action.autoconfig")); //$NON-NLS-1$
      autoConfigButton.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_FILL));
      autoConfigButton.addSelectionListener(new SelectionAdapter() {
        @Override
        public void widgetSelected(SelectionEvent e) {
          String serverUrl = getServerUrl();
          Object[] checkedElements = viewer.getCheckedElements();
          SonarProject[] projects = new SonarProject[checkedElements.length];
          for (int i = 0; i < checkedElements.length; i++) {
            projects[i] = (SonarProject) checkedElements[i];
          }

          try {
            getWizard().getContainer().run(true, false, new AssociateProjects(serverUrl, projects));
          } catch (InvocationTargetException ex) {
            SonarLogger.log(ex);
          } catch (InterruptedException ex) {
            SonarLogger.log(ex);
          }
        }
      });

      setControl(container);
    }

    private String getServerUrl() {
      Host host = (Host) SelectionUtils.getSingleElement(comboViewer.getSelection());
      return host.getHost();
    }

    public boolean finish() {
      Object[] checked = viewer.getCheckedElements();
      for (Object obj : checked) {
        SonarProject sonarProject = (SonarProject) obj;
        try {
          IProject project = sonarProject.getProject();
          ProjectProperties properties = ProjectProperties.getInstance(project);
          properties.setUrl(getServerUrl());
          properties.setArtifactId(sonarProject.getArtifactId());
          properties.setGroupId(sonarProject.getGroupId());
          properties.setBranch(sonarProject.getBranch());
          properties.save();
          ToggleNatureAction.enableNature(project);
        } catch (CoreException e) {
          SonarLogger.log(e);
          return false;
        }
      }
      return true;
    }

    public class AssociateProjects implements IRunnableWithProgress {

      private String url;
      private SonarProject[] projects;

      public AssociateProjects(String url, SonarProject[] projects) {
        Assert.isNotNull(url);
        Assert.isNotNull(projects);
        this.url = url;
        this.projects = projects;
      }

      public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
        monitor.beginTask("Requesting " + url, IProgressMonitor.UNKNOWN);
        ResourceQuery query = new ResourceQuery().setScopes(Resource.SCOPE_SET).setQualifiers(Resource.QUALIFIER_PROJECT, Resource.QUALIFIER_MODULE);
        Sonar sonar = SonarPlugin.getServerManager().getSonar(url);
        List<Resource> resources = sonar.findAll(query);
        for (SonarProject sonarProject : projects) {
          for (Resource resource : resources) {
            if (resource.getKey().endsWith(":" + sonarProject.getName())) {
              sonarProject.setGroupId(StringUtils.substringBefore(resource.getKey(), ":"));
              sonarProject.setArtifactId(sonarProject.getName());
            }
          }
        }
        monitor.done();
      }

    }

    public class SonarProject extends AbstractModelObject {
      public static final String PROPERTY_PROJECT_NAME = "name";
      public static final String PROPERTY_GROUP_ID = "groupId";
      public static final String PROPERTY_ARTIFACT_ID = "artifactId";
      public static final String PROPERTY_BRANCH = "branch";

      private IProject project;
      private String groupId;
      private String artifactId;
      private String branch;

      public SonarProject(IProject project) {
        this.project = project;
        this.groupId = "";
        this.artifactId = "";
        this.branch = "";
      }

      public IProject getProject() {
        return project;
      }

      public String getGroupId() {
        return groupId;
      }

      public void setGroupId(String groupId) {
        firePropertyChange("groupId", this.groupId, this.groupId = groupId);
      }

      public String getArtifactId() {
        return artifactId;
      }

      public void setArtifactId(String artifactId) {
        firePropertyChange("artifactId", this.artifactId, this.artifactId = artifactId);
      }

      public String getName() {
        return project.getName();
      }

      public void setBranch(String branch) {
        firePropertyChange("branch", this.branch, this.branch = branch);
      }

      public String getBranch() {
        return branch;
      }
    }

  }

}