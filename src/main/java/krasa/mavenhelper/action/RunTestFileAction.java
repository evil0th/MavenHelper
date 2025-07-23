package krasa.mavenhelper.action;

import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClassOwner;
import com.intellij.psi.PsiFile;
import krasa.mavenhelper.analyzer.ComparableVersion;
import krasa.mavenhelper.icons.MyIcons;
import krasa.mavenhelper.model.ApplicationSettings;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.execution.MavenRunnerParameters;
import org.jetbrains.idea.maven.model.MavenPlugin;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.utils.actions.MavenActionUtil;

import javax.swing.*;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class RunTestFileAction extends DumbAwareAction {
	private final Logger LOG = Logger.getInstance("#" + getClass().getCanonicalName());
	protected boolean alwaysVisible;

	public RunTestFileAction() {
		super("Test file", "Run current File with Maven", MyIcons.MAVEN_LOGO);
	}

	public RunTestFileAction(String text, @Nullable String description, @Nullable Icon icon) {
		super(text, description, icon);
	}

	@Override
	public @NotNull ActionUpdateThread getActionUpdateThread() {
		return ActionUpdateThread.BGT;
	}

	@Override
	public void actionPerformed(AnActionEvent e) {
		MavenProject mavenProject = Utils.getMavenProject(e.getDataContext());
		if (mavenProject != null) {

			PsiFile psiFile = LangDataKeys.PSI_FILE.getData(e.getDataContext());
			if (psiFile instanceof PsiClassOwner) {
				List<String> goals = getGoals(e, (PsiClassOwner) psiFile, mavenProject);

				final DataContext context = e.getDataContext();
				MavenRunnerParameters params = new MavenRunnerParameters(true, mavenProject.getDirectory(), null, goals,
						MavenActionUtil.getProjectsManager(context).getExplicitProfiles());
				params.setResolveToWorkspace(ApplicationSettings.get().isResolveWorkspaceArtifacts());
				run(context, params);
			} else {
				Messages.showWarningDialog(e.getProject(), "Cannot run for current file", "Maven Test File");
			}
		}
	}

	protected void run(DataContext context, MavenRunnerParameters params) {
		Project project = MavenActionUtil.getProject(context);
		ProgramRunnerUtils.run(project, params);
	}

	protected List<String> getGoals(AnActionEvent e, PsiClassOwner psiFile, MavenProject mavenProject) {
		List<String> goals = new ArrayList<String>();
		boolean skipTests = isSkipTests(mavenProject);
		// so many possibilities...
		if (skipTests || isExcludedFromSurefire(psiFile, mavenProject)) {
			MavenPlugin failsafePlugin = mavenProject.findPlugin("org.apache.maven.plugins", "maven-failsafe-plugin");
			if (failsafePlugin != null) {
				addFailSafeParameters(e, psiFile, goals, failsafePlugin);
			} else {
				addSurefireParameters(e, psiFile, goals);
			}
			goals.add("verify");
		} else {
			addSurefireParameters(e, psiFile, goals);
			goals.add("test-compile");
			goals.add("surefire:test");
		}

		return goals;
	}

	private void addSurefireParameters(AnActionEvent e, PsiClassOwner psiFile, List<String> goals) {
		goals.add("-Dtest=" + Utils.getTestArgument(psiFile, ConfigurationContext.getFromContext(e.getDataContext())));
	}

	private void addFailSafeParameters(AnActionEvent e, PsiClassOwner psiFile, List<String> goals, MavenPlugin mavenProjectPlugin) {
		ComparableVersion version = new ComparableVersion(mavenProjectPlugin.getVersion());
		ComparableVersion minimumForMethodTest = new ComparableVersion("2.7.3");
		if (minimumForMethodTest.compareTo(version) == 1) {
			goals.add("-Dit.test=" + Utils.getTestArgumentWithoutMethod(e, psiFile));
		} else {
			goals.add("-Dit.test=" + Utils.getTestArgument(psiFile, ConfigurationContext.getFromContext(e.getDataContext())));
		}
	}

	private boolean isExcludedFromSurefire(PsiClassOwner psiFile, MavenProject mavenProject) {
		boolean excluded = false;
		try {
			Element pluginConfiguration = mavenProject.getPluginConfiguration("org.apache.maven.plugins",
					"maven-surefire-plugin");
			excluded = false;
			String fullName = null;
			if (pluginConfiguration != null) {
				Element excludes = pluginConfiguration.getChild("excludes");
				if (excludes != null) {
					List<Element> exclude = excludes.getChildren("exclude");
					for (Element element : exclude) {
						if (fullName == null) {
							fullName = getPsiFilePath(psiFile);
						}
						try {
							excluded = matchClassRegexPatter(fullName, element.getText());
						} catch (IllegalArgumentException e) {
							LOG.warn("MavenProject Invalid glob pattern: " + element.getText());
						}
						if (excluded) {
							break;
						}
					}
				}
			}
		} catch (Exception e) {
			LOG.warn(e);
		}
		return excluded;
	}

	protected static boolean matchClassRegexPatter(String testClassFile, String classPattern) {
		FileSystem fs = FileSystems.getDefault();
		PathMatcher matcher = fs.getPathMatcher("glob:" + classPattern);
		return matcher.matches(Paths.get(testClassFile));
	}

	@NotNull
	private String getPsiFilePath(PsiClassOwner psiFile) {
		String packageName = psiFile.getPackageName();
		String fullName;
		if (packageName.isEmpty()) {
			fullName = psiFile.getName();
		} else {
			fullName = packageName.replace(".", "/") + "/" + psiFile.getVirtualFile().getName();
		}
		return fullName;
	}

	private boolean isSkipTests(MavenProject mavenProject) {
		Element pluginConfiguration = mavenProject.getPluginConfiguration("org.apache.maven.plugins",
				"maven-surefire-plugin");
		boolean skipTests = false;
		if (pluginConfiguration != null) {
			Element skip;
			if ((skip = pluginConfiguration.getChild("skip")) != null) {
				skipTests = Boolean.parseBoolean(skip.getText());
			} else if ((skip = pluginConfiguration.getChild("skipTests")) != null) {
				skipTests = Boolean.parseBoolean(skip.getText());
			}
		}
		return skipTests;
	}

	@Override
	public void update(AnActionEvent e) {
		super.update(e);
		Project eventProject = getEventProject(e);
		if (eventProject == null) {
			return;
		}
		if (DumbService.isDumb(eventProject)) {
			Presentation p = e.getPresentation();
			p.setVisible(false);
			return;
		}
		if (!MavenActionUtil.isMavenizedProject(e.getDataContext())) {
			Presentation p = e.getPresentation();
			p.setVisible(false);
			return;
		}

		//TODO #71  perhaps by com.intellij.execution.actions.BaseRunConfigurationAction.fullUpdate
		PsiFile psiFile = PlatformDataKeys.PSI_FILE.getData(e.getDataContext());
		boolean isTest = psiFile != null;
		boolean available = isAvailable(e);
		boolean visible = isVisible(e);

		Presentation p = e.getPresentation();
		p.setEnabled(isTest && available);
		p.setVisible(alwaysVisible || (isTest && available));
		if (isTest && available && visible) {
			VirtualFile virtualFile = psiFile.getVirtualFile();
			String name;
			if (virtualFile != null) {
				name = virtualFile.getNameWithoutExtension();
			} else {
				name = psiFile.getName();
			}
			p.setText(getText(name));
		}
	}

	protected String getText(String s) {
		return "Test " + s;
	}

	protected boolean isAvailable(AnActionEvent e) {
		boolean isFile = false;
		VirtualFile data = PlatformDataKeys.VIRTUAL_FILE.getData(e.getDataContext());
		if (data != null) {
			isFile = !data.isDirectory();
		}

		return isFile && MavenActionUtil.hasProject(e.getDataContext());
	}

	protected boolean isVisible(AnActionEvent e) {
		MavenProject mavenProject = Utils.getMavenProject(e.getDataContext());
		return mavenProject != null;
	}

	public AnAction alwaysVisible() {
		alwaysVisible = true;
		return this;
	}
}
