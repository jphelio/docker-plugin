package com.nirima.jenkins.plugins.docker;

import shaded.com.google.common.base.Objects;
import shaded.com.google.common.base.Preconditions;
import shaded.com.google.common.base.Strings;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.DockerException;
import com.github.dockerjava.api.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Image;
import com.nirima.jenkins.plugins.docker.action.DockerBuildAction;

import hudson.Extension;
import hudson.model.*;
import hudson.model.queue.CauseOfBlockage;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;


public class DockerSlave extends AbstractCloudSlave {

    private static final Logger LOGGER = Logger.getLogger(DockerSlave.class.getName());

    public final DockerTemplate dockerTemplate;
    public final String containerId;

    private transient Run theRun;

    @DataBoundConstructor
    public DockerSlave(DockerTemplate dockerTemplate, String containerId, String name, String nodeDescription,
                       String remoteFS, int numExecutors, Mode mode, Integer memoryLimit, Integer cpuShares,
                       String labelString, ComputerLauncher launcher, RetentionStrategy retentionStrategy,
                       List<? extends NodeProperty<?>> nodeProperties)
            throws Descriptor.FormException, IOException {
        super(name, nodeDescription, remoteFS, numExecutors, mode, labelString, launcher, retentionStrategy, nodeProperties);
        Preconditions.checkNotNull(dockerTemplate);
        Preconditions.checkNotNull(containerId);

        this.dockerTemplate = dockerTemplate;
        this.containerId = containerId;
    }

    public DockerCloud getCloud() {
        DockerCloud theCloud = dockerTemplate.getParent();

        if( theCloud == null ) {
            throw new RuntimeException("Docker template " + dockerTemplate + " has no parent ");
        }

        return theCloud;
    }

    @Override
    public String getDisplayName() {
        return name;
    }

    public void setRun(Run run) {
        this.theRun = run;
    }

    @Override
    public DockerComputer createComputer() {
        return new DockerComputer(this);
    }

    @Override
    public CauseOfBlockage canTake(Queue.BuildableItem item) {
        if (item.task instanceof Queue.FlyweightTask) {
          return new CauseOfBlockage() {
            public String getShortDescription() {
                return "Don't run FlyweightTask on Docker node";
            }
          };
        }
        return super.canTake(item);
    }

    public boolean containerExistsInCloud() {
        try {
            DockerClient client = getClient();
            client.inspectContainerCmd(containerId).exec();
            return true;
        } catch(Exception ex) {
            return false;
        }
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {


        try {
            toComputer().disconnect(null);

            try {
                DockerClient client = getClient();
                client.stopContainerCmd(containerId).exec();
            } catch(Exception ex) {
                LOGGER.log(Level.SEVERE, "Failed to stop instance " + containerId + " for slave " + name + " due to exception", ex);
            }

            // If the run was OK, then do any tagging here
            if( theRun != null ) {
                try {
                    slaveShutdown(listener);
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Failure to slaveShutdown instance " + containerId+ " for slave " + name , e);
                }
            }

            try {
                DockerClient client = getClient();
                client.removeContainerCmd(containerId).exec();
            } catch(Exception ex) {
                LOGGER.log(Level.SEVERE, "Failed to remove instance " + containerId + " for slave " + name + " due to exception",ex);
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failure to terminate instance " + containerId + " for slave " + name ,e);
        }
    }

    private void slaveShutdown(TaskListener listener) throws DockerException, IOException {
        // The slave has stopped. Should we commit / tag / push ?
        if(theRun.getResult() != Result.SUCCESS)
            return;

        if(!getJobProperty().tagOnCompletion) {
            addJenkinsAction(null);
            return;
        }

        DockerClient client = getClient();
        String imageName = theRun.getParent().getDisplayName();
        String customRepository=getJobProperty().getCustomRepository();
        if(customRepository!=null)
            imageName=customRepository+imageName;
        
        String tag_image = "";
        try{
            tag_image = performActions(tag_image, client, imageName, listener);
        }
        catch(Exception ex) {
            LOGGER.log(Level.SEVERE, "Could not perform configured action: ", ex);
            tag_image = retryToPerformActions(tag_image, client, imageName, listener, "Retrying once...");
            if(tag_image == (null)){
                tag_image = retryToPerformActions(tag_image, client, imageName, listener, "Retrying second time...");
            }
        }
        removeImage(client, tag_image);
    }

    private String performActions(String tag_image, 
                                        DockerClient client, 
                                        String imageName, 
                                        TaskListener listener) throws IOException, NotFoundException {
        String tagToken = getAdditionalTag(listener);
            //commit
        tag_image = commitImage(client, imageName, tagToken);
            // Tag it with the jenkins name
        tagImage(client, tag_image, imageName, tagToken);
            //push
        pushImageToRepo(client, tag_image, imageName, tagToken);
        return tag_image;
    }
    
    private String getAdditionalTag(TaskListener listener) {
        // Do a macro expansion on the addJenkinsAction token

        // Job property
        String tagToken = getJobProperty().additionalTag;

        // Do any macro expansions
        try {
            if(!Strings.isNullOrEmpty(tagToken)  )
                tagToken = TokenMacro.expandAll((AbstractBuild) theRun, listener, tagToken);
            else //default tag = latest
                tagToken = TokenMacro.expandAll((AbstractBuild) theRun, listener, "latest");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "can't expand macroses", e);
        }
        return tagToken;
    }
    
    private String commitImage(DockerClient client, String imageName, String tagToken) throws IOException, NotFoundException {
        String tag_image = client.commitCmd(containerId)
                .withRepository(imageName)
                .withTag(tagToken) // allowed only ([a-zA-Z_][a-zA-Z0-9_]*)
                .withAuthor("Jenkins")
                .exec();
        
        return tag_image;
    }
    
    private void tagImage(DockerClient client, String tag_image, String imageName, String tagToken) throws IOException {
        addJenkinsAction(tag_image);
        client.tagImageCmd(tag_image, imageName,tagToken).withForce(getJobProperty().isForceTag()).exec();
    }

    private void pushImageToRepo(DockerClient client, String tag_image, String imageName, String tagToken) throws IOException, NotFoundException {
        addJenkinsAction(tagToken);
        if( getJobProperty().pushOnSuccess ) {
            client.pushImageCmd(tag_image).withName(imageName).withTag(tagToken).exec();
        }
    }
    
    private void removeImage(DockerClient client, String tag_image) throws NotFoundException {
        if( getJobProperty().cleanImages ) {
            client.removeImageCmd(tag_image)
                    .withForce()
                    .exec();
        }
    }
    
    private String retryToPerformActions(String tag_image, DockerClient client, String imageName, TaskListener listener, String message) throws NotFoundException, IOException {
        LOGGER.log(Level.SEVERE, message);
        try{
            tag_image = performActions(tag_image, client, imageName, listener);
        }
        catch(Exception e){
            LOGGER.log(Level.SEVERE, "Retry failed: ", e);
            return null;
        }
        return tag_image;
    }

    /**
     * Add a built on docker action.
     * @param tag_image
     * @throws IOException
     */
    private void addJenkinsAction(String tag_image) throws IOException {
        theRun.addAction( new DockerBuildAction(getCloud().serverUrl, containerId, tag_image, dockerTemplate.remoteFsMapping) );
        theRun.save();
    }

    public DockerClient getClient() {
        return getCloud().connect();
    }

    /**
     * Called when the slave is connected to Jenkins
     */
    public void onConnected() {

    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("name", name)
                .add("containerId", containerId)
                .add("template", dockerTemplate)
                .toString();
    }

    private DockerJobProperty getJobProperty() {

        try {
            DockerJobProperty p = (DockerJobProperty) ((AbstractBuild) theRun).getProject().getProperty(DockerJobProperty.class);

            if (p != null)
                return p;
        } catch(Exception ex) {
            // Don't care.
        }
        // Safe default
        return new DockerJobProperty(false, null, false, false, null, true);
    }

    @Extension
	public static final class DescriptorImpl extends SlaveDescriptor {

    	@Override
		public String getDisplayName() {
			return "Docker Slave";
    	};

		@Override
		public boolean isInstantiable() {
			return false;
		}

	}
}
