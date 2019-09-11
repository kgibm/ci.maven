/**
 * (C) Copyright IBM Corporation 2014, 2019.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.openliberty.tools.maven.applications;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import io.openliberty.tools.maven.utils.SpringBootUtil;
import io.openliberty.tools.common.plugins.config.ApplicationXmlDocument;
import io.openliberty.tools.common.plugins.config.LooseConfigData;
import io.openliberty.tools.common.plugins.config.ServerConfigDocument;

/**
 * Copy applications to the specified directory of the Liberty server.
 */
@Mojo(name = "deploy", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class DeployMojo extends DeployMojoSupport {

    protected void doExecute() throws Exception {
        if (skip) {
            return;
        }
        checkServerHomeExists();
        checkServerDirectoryExists();
        
        // Delete our generated configDropins XML (a new one will be generated if necessary)
        cleanupPreviousExecution();

        // update target server configuration
        copyConfigFiles();
        exportParametersToXml();
        
        boolean installDependencies = false;
        boolean installProject = false;
                
        switch (getDeployPackages()) {
            case "all":
                installDependencies = true;
                installProject = true;
                break;
            case "dependencies":
                installDependencies = true;
                break;
            case "project":
                installProject = true;
                break;              
            case "spring-boot-project":
                installSpringBootApp();
                break;
            default:
                return;
        }
              
        if (installDependencies) {
            installDependencies();
        }
        if (installProject) {
            installProject();
        }
        
        // create application configuration in configDropins if it is not configured
        if (applicationXml.hasChildElements()) {
            log.warn(messages.getString("warn.install.app.add.configuration"));
            applicationXml.writeApplicationXmlDocument(serverDirectory);
        }
    }

    private void installSpringBootApp() throws Exception {
        File fatArchiveSrc = SpringBootUtil.getSpringBootUberJAR(project, getLog());
        
        // Check if the archiveSrc is executable and then invokeSpringUtilCommand. 
        if(io.openliberty.tools.common.plugins.util.SpringBootUtil.isSpringBootUberJar(fatArchiveSrc)) {
            File thinArchiveTarget = getThinArchiveTarget(fatArchiveSrc);
            File libIndexCacheTarget = getLibIndexCacheTarget();
            
            validateAppConfig(thinArchiveTarget.getName(), project.getArtifactId(), true);
            invokeSpringBootUtilCommand(installDirectory, fatArchiveSrc.getCanonicalPath(), thinArchiveTarget.getCanonicalPath(), libIndexCacheTarget.getCanonicalPath());
        } else {
            throw new MojoExecutionException(fatArchiveSrc.getCanonicalPath() +" file is not an executable archive. "
                    + "The repackage goal of the spring-boot-maven-plugin must be configured to run first in order to create the required executable archive.");
        }
    }
    
    private File getThinArchiveTarget(File archiveSrc) {
        String appsDirName = getAppsDirectory();
        File archiveTarget = null;
        File appsDir = null;
        
        if("apps".equals(appsDirName)){
            appsDir = new File(serverDirectory, appsDirName);        
        } else if ("dropins".equals(appsDirName)) {
            appsDir = new File(serverDirectory, appsDirName+"/spring");         
        }       
        archiveTarget = new File(appsDir, "thin-" + archiveSrc.getName());
        return archiveTarget;
    }
    
    private File getLibIndexCacheTarget() {
        // Set shared directory ${installDirectory}/usr/shared/
        File sharedDirectory = new File(userDirectory, "shared");
        
        //Set shared resources directory ${installDirectory}/usr/shared/resources/
        File sharedResourcesDirectory = new File(sharedDirectory, "resources");
        
        if(!sharedResourcesDirectory.exists()) {
            sharedResourcesDirectory.mkdirs();
        }
        File libIndexCacheTarget = new File(sharedResourcesDirectory, "lib.index.cache");
        return libIndexCacheTarget;
    }

    protected void installDependencies() throws Exception {
        Set<Artifact> artifacts = project.getArtifacts();
        log.debug("Number of compile dependencies for " + project.getArtifactId() + " : " + artifacts.size());
        
        for (Artifact artifact : artifacts) {
            // skip if not an application type supported by Liberty
            if (!isSupportedType(artifact.getType())) {
                continue;
            }
            // skip assemblyArtifact if specified as a dependency
            if (assemblyArtifact != null && matches(artifact, assemblyArtifact)) {
                continue;
            }
            if (artifact.getScope().equals("compile")) {
                if (isSupportedType(artifact.getType())) {
                    if (looseApplication && isReactorMavenProject(artifact)) {
                        MavenProject dependProj = getReactorMavenProject(artifact);
                        if (shouldDeploy()) {
                            appArchive = new File(project.getBuild().getDirectory(), getLooseConfigFileName(dependProj));
                            //Generates looseAppFile in the project's buildDir
                            installLooseApplication(dependProj, appArchive);
                            //Loose app xml deployed to server
                            deployApp();
                        } else {
                            //Writes loose app xml straight to serverDir
                            installLooseApplication(dependProj, new File(new File(serverDirectory, getAppsDirectory()), getLooseConfigFileName(dependProj)));
                        }
                    } else { //Deploying or installing the reactor project artifacts
                        if (shouldDeploy()) {
                            appArtifact = artifact;
                            deployApp();
                        } else {
                            installApp(resolveArtifact(artifact));
                        }
                    }
                } else {
                    log.warn(MessageFormat.format(messages.getString("error.application.not.supported"),
                            project.getId()));
                }
            }
        }
    }
    
    protected void installProject() throws Exception {
        if (isSupportedType(project.getPackaging())) {
            if (looseApplication) {
                //Either write loose app xml file to buildDir and deploy, or install to serverDir
                if (shouldDeploy()) {
                    appArchive = new File(project.getBuild().getDirectory(), getLooseConfigFileName(project));
                    //Generates looseAppFile in the project's buildDir
                    installLooseApplication(project, appArchive);
                    //Loose app xml deployed to server
                    deployApp();
                } else {
                    //Writes loose app xml straight to serverDir
                    installLooseApplication(project, new File(new File(serverDirectory, getAppsDirectory()), getLooseConfigFileName(project)));
                }
            } else {
                if (shouldDeploy()) {
                    deployApp();
                } else {
                    installApp(project.getArtifact());
                }
            }
        } else {
            throw new MojoExecutionException(
                    MessageFormat.format(messages.getString("error.application.not.supported"), project.getId()));
        }
    }

    private void installLooseApplication(MavenProject proj, File looseConfigFile) throws Exception {
        String looseConfigFileName = getLooseConfigFileName(proj);
        String application = looseConfigFileName.substring(0, looseConfigFileName.length() - 4);
        LooseConfigData config = new LooseConfigData();

        switch (proj.getPackaging()) {
            case "war":
                validateAppConfig(application, proj.getArtifactId());
                log.info(MessageFormat.format(messages.getString("info.install.app"), looseConfigFileName));
                installLooseConfigWar(proj, config);
                deleteApplication(new File(serverDirectory, "apps"), looseConfigFile);
                deleteApplication(new File(serverDirectory, "dropins"), looseConfigFile);
                config.toXmlFile(looseConfigFile);
                break;
            case "ear":
                validateAppConfig(application, proj.getArtifactId());
                log.info(MessageFormat.format(messages.getString("info.install.app"), looseConfigFileName));
                installLooseConfigEar(proj, config);
                deleteApplication(new File(serverDirectory, "apps"), looseConfigFile);
                deleteApplication(new File(serverDirectory, "dropins"), looseConfigFile);
                config.toXmlFile(looseConfigFile);
                break;
            case "liberty-assembly":
                if (mavenWarPluginExists(proj) || new File(proj.getBasedir(), "src/main/webapp").exists()) {
                    validateAppConfig(application, proj.getArtifactId());
                    log.info(MessageFormat.format(messages.getString("info.install.app"), looseConfigFileName));
                    installLooseConfigWar(proj, config);
                    deleteApplication(new File(serverDirectory, "apps"), looseConfigFile);
                    deleteApplication(new File(serverDirectory, "dropins"), looseConfigFile);
                    config.toXmlFile(looseConfigFile);
                } else {
                    log.debug("The liberty-assembly project does not contain the maven-war-plugin or src/main/webapp does not exist.");
                }
                break;
            default:
                log.info(MessageFormat.format(messages.getString("info.loose.application.not.supported"),
                        proj.getPackaging()));
                installApp(proj.getArtifact());
                break;
        }
    }
    
    private void cleanupPreviousExecution() {
        if (ApplicationXmlDocument.getApplicationXmlFile(serverDirectory).exists()) {
            ApplicationXmlDocument.getApplicationXmlFile(serverDirectory).delete();
            ServerConfigDocument.markInstanceStale();
        }
    }

    private boolean shouldDeploy() throws MojoExecutionException {
        try {
            return new File(serverDirectory.getCanonicalPath()  + "/workarea/.sRunning").exists();
        } catch (IOException ioe) {
            throw new MojoExecutionException("Could not get the server directory to determine the state of the server.");
        }
    }

    private boolean mavenWarPluginExists(MavenProject proj) {
        MavenProject currentProject = proj;
        while(currentProject != null) {
            List<Object> plugins = new ArrayList<Object>(currentProject.getBuildPlugins());
            plugins.addAll(currentProject.getPluginManagement().getPlugins());
            for(Object o : plugins) {
                if(o instanceof Plugin) {
                    Plugin plugin = (Plugin) o;
                    if(plugin.getGroupId().equals("org.apache.maven.plugins") && plugin.getArtifactId().equals("maven-war-plugin")) {
                        return true;
                    }
                }
            }
            currentProject = currentProject.getParent();
        }
        return false;
    }
   
}
