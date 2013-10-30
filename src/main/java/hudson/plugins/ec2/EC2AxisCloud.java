package hudson.plugins.ec2;

import static org.kohsuke.stapler.Stapler.getCurrentRequest;
import hudson.EnvVars;
import hudson.Extension;
import hudson.matrix.MatrixBuild.MatrixBuildExecution;
import hudson.model.Api;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.Cloud;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.util.StreamTaskListener;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

import jenkins.model.Jenkins;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.lang.time.StopWatch;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.KeyPair;

@ExportedBean
public class EC2AxisCloud extends AmazonEC2Cloud {
	private static final String END_LABEL_SEPARATOR = "-";
	private static final String SLAVE_MATRIX_ENV_VAR_NAME = "MATRIX_EXEC_ID";
	private static final String SLAVE_NUM_SEPARATOR = "__";
	private final EC2AxisPrivateKey ec2PrivateKey;

	@DataBoundConstructor
	public EC2AxisCloud(String accessId, String secretKey, String region, String privateKey, String instanceCapStr, List<SlaveTemplate> templates) {
		super(accessId,secretKey,region, privateKey,instanceCapStr,replaceByEC2AxisSlaveTemplates(templates));
		ec2PrivateKey = new EC2AxisPrivateKey(privateKey);
	}
	
	public Api getApi() {
        return new Api(this);
    }
	
	@Exported
	public String getFoo() {
		StaplerRequest currentRequest = getCurrentRequest();
		String parameter = currentRequest.getParameter("myId");
		return "foo/"+parameter;
	}
	
	@Exported
	public IHaveAnApi getMe() {
		return new IHaveAnApi();
	}
	
	public boolean acceptsLabel(Label label) {
		return getTemplateGivenLabel(label) != null;
	}
	
	@Override
	public Ec2AxisSlaveTemplate getTemplate(Label label) {
		String displayName = label.getDisplayName();
		if (displayName == null)
			return null;
		
    	String labelPrefix = StringUtils.substringBefore(displayName,SLAVE_NUM_SEPARATOR);
		LabelAtom prefixAtom = new LabelAtom(labelPrefix);
    	Ec2AxisSlaveTemplate template = (Ec2AxisSlaveTemplate)super.getTemplate(prefixAtom);
    	if (template == null)
    		return null;
    	template.setInstanceLabel(displayName);
		return template;		
	}

	public static EC2AxisCloud getCloudToUse(String ec2label) {
		Iterator<Cloud> iterator = Jenkins.getInstance().clouds.iterator();
		EC2AxisCloud cloudToUse = null;
		while(iterator.hasNext()) {
			Cloud next = iterator.next();
			if (next instanceof EC2AxisCloud) {
				if (((EC2AxisCloud)next).acceptsLabel(new LabelAtom(ec2label)))
					cloudToUse = (EC2AxisCloud) next;
			}
		}
		return cloudToUse;
	}

	final static ReentrantLock  labelAllocationLock = new ReentrantLock();
	
	public List<String> allocateSlavesLabels(
			MatrixBuildExecution buildContext, 
			String ec2Label, 
			Integer numberOfSlaves, 
			Integer instanceBootTimeoutLimit) 
	{
		try {
			labelAllocationLock.lockInterruptibly();
			
			final PrintStream logger = buildContext.getListener().getLogger();
			LinkedList<String> onlineAndAvailableLabels = findOnlineEligibleSlavesToAllocate(logger, ec2Label, numberOfSlaves);
			int countOfRemainingLabelsToCreate = numberOfSlaves - onlineAndAvailableLabels.size();
			LinkedList<String> allLabels = new LinkedList<String>();
			allLabels.addAll(onlineAndAvailableLabels);

			if (countOfRemainingLabelsToCreate > 0) {
				int nextMatrixId = onlineAndAvailableLabels.size()+1;
				LinkedList<String> newLabels = createMissingSlaves(
						buildContext,
						ec2Label, 
						countOfRemainingLabelsToCreate, 
						nextMatrixId);
				allLabels.addAll(newLabels);
			}
			
			return allLabels;
		} catch (InterruptedException e1) {
			throw new RuntimeException(e1);
		} finally {
			labelAllocationLock.unlock();
		}
	}

	private LinkedList<String> createMissingSlaves(
			MatrixBuildExecution buildContext, 
			String ec2Label, 
			int remainingLabelsToCreate,
			int nextMatrixId) 
	{
		PrintStream logger = buildContext.getListener().getLogger();
		LinkedList<String> newLabels = allocateNewLabels(ec2Label, remainingLabelsToCreate, logger);
		
		try {
			allocateSlavesAndLaunchThem(ec2Label, logger, newLabels, nextMatrixId);
			return newLabels;
		} catch (Exception e) {
			logger.print(ExceptionUtils.getFullStackTrace(e));
			throw new RuntimeException(e);
		}
	}
	
	private void allocateSlavesAndLaunchThem(
			String ec2Label,
			final PrintStream logger, 
			LinkedList<String> allocatedLabels, 
			int nextMatrixId) throws IOException 
	{
		logger.println("Will provision instances for requested labels: " + StringUtils.join(allocatedLabels,","));
		Ec2AxisSlaveTemplate slaveTemplate = getTemplate(new LabelAtom(ec2Label));
		StreamTaskListener taskListener = new StreamTaskListener(logger,Charset.forName("UTF-8"));
		
		List<EC2AbstractSlave> allocatedSlaves = slaveTemplate.provisionMultipleSlaves(taskListener, allocatedLabels.size());
		Iterator<String> labelIt = allocatedLabels.iterator();
		int matrixIdSeq = nextMatrixId;
		
		for (EC2AbstractSlave ec2Slave : allocatedSlaves) {
			logger.println("Setting up labels and environment variables for " + ec2Slave.getDisplayName());
			Hudson.getInstance().addNode(ec2Slave);
			String slaveLabel = labelIt.next();
			ec2Slave.setLabelString(slaveLabel);
			EnvVars slaveEnvVars = getSlaveEnvVars(ec2Slave);
			slaveEnvVars.put(SLAVE_MATRIX_ENV_VAR_NAME, ""+matrixIdSeq++);
		}
	}

	private EnvVars getSlaveEnvVars(EC2AbstractSlave provisionedSlave) {
		EnvironmentVariablesNodeProperty v = provisionedSlave.getNodeProperties().get(EnvironmentVariablesNodeProperty.class);
		if (v == null) {
			v = new EnvironmentVariablesNodeProperty();
			provisionedSlave.getNodeProperties().add(v);
		}
		return v.getEnvVars();
	}
	
	private synchronized LinkedList<String> allocateNewLabels(String ec2Label, Integer numberOfSlaves, PrintStream logger) 
	{
		LinkedList<String> allocatedLabels = new LinkedList<String>();
		logger.println("Starting creation of new labels to assign");
		Integer slavesToComplete = numberOfSlaves - allocatedLabels.size();
		int currentLabelNumber = 0;
		for (int i = 0; i < slavesToComplete; i++) {
			// TODO: make a method to return the next "available" label number
			int slaveNumber = currentLabelNumber++;
			String newLabel = ec2Label + END_LABEL_SEPARATOR + String.format("%03d", slaveNumber);
			allocatedLabels.add(newLabel);
			logger.println("New label " + newLabel + " will be created.");
		}
		return allocatedLabels;
	}

	private LinkedList<String> findOnlineEligibleSlavesToAllocate(
			PrintStream logger,
			String ec2Label, 
			Integer numberOfSlaves) 
	{
		logger.println("Starting selection of labels with idle executors for job");
		final LinkedList<String> onlineAndAvailableLabels = new LinkedList<String>();
		TreeSet<Label> sortedLabels = getSortedLabels();
		int matrixId = 1;
		logger.println("Will check " + sortedLabels.size() +" labels");
		for (Label label : sortedLabels) {
			logger.println("Checking label " + label.getDisplayName());
			String labelString = label.getDisplayName();
			
			if (!isExpectedLabel(ec2Label, labelString)) continue;
			
			if (!isLabelUsedByMatrixJobs(labelString)) continue;
			
			if (!hasAvailableNodes(logger, label)) continue;
			
			logger.println(labelString + " has online and available nodes.");
			Node firstNode = label.getNodes().iterator().next();
			boolean isNodeEc2Slave = !(firstNode instanceof EC2AbstractSlave);
			if (isNodeEc2Slave)
				continue;
			
			EnvVars slaveEnvVars = getSlaveEnvVars((EC2AbstractSlave) firstNode);
			slaveEnvVars.put(SLAVE_MATRIX_ENV_VAR_NAME, matrixId+"");
			onlineAndAvailableLabels.add(labelString);
			matrixId++;
			
			if (onlineAndAvailableLabels.size() >= numberOfSlaves)
				break;
		}
		logger.println("Online labels found : " + onlineAndAvailableLabels.size());
		return onlineAndAvailableLabels;
	}
	
	private boolean isLabelUsedByMatrixJobs(String labelString) {
		final String[] prefixAndSlaveNumber = labelString.split("\\"+END_LABEL_SEPARATOR);
		boolean hasSuffix = prefixAndSlaveNumber.length != 1;
		return hasSuffix;
	}

	private boolean isExpectedLabel(String ec2Label, String labelString) {
		return labelString.startsWith(ec2Label);
	}

	private TreeSet<Label> getSortedLabels() {
		Set<Label> labels = Jenkins.getInstance().getLabels();
		TreeSet<Label> sortedLabels = new TreeSet<Label>(new Comparator<Label>() {

			@Override
			public int compare(Label o1, Label o2) {
				return o1.getDisplayName().compareTo(o2.getDisplayName());
			}
		});
		sortedLabels.addAll(labels);
		return sortedLabels;
	}

	private static List<SlaveTemplate> replaceByEC2AxisSlaveTemplates(List<SlaveTemplate> templates) {
		List<SlaveTemplate> ec2axisTemplates = new LinkedList<SlaveTemplate>();
		for (SlaveTemplate slaveTemplate : templates) {
			ec2axisTemplates.add(new Ec2AxisSlaveTemplate(slaveTemplate));
		}
		return ec2axisTemplates;
	}

	private Ec2AxisSlaveTemplate getTemplateGivenLabel(Label label) {
		String displayName = label.getDisplayName();
		if (displayName == null)
			return null;
		
    	String labelPrefix = StringUtils.substringBefore(displayName,SLAVE_NUM_SEPARATOR);
		LabelAtom prefixAtom = new LabelAtom(labelPrefix);
    	Ec2AxisSlaveTemplate template = (Ec2AxisSlaveTemplate)super.getTemplate(prefixAtom);
		return template;
	}
	

	private boolean hasAvailableNodes(PrintStream logger, Label label) {
		Set<Node> nodes = label.getNodes();
		boolean hasNodeOnlineAndAvailable = hasNodeOnlineAndAvailable(logger, label, nodes);
		if (!hasNodeOnlineAndAvailable) 
			logger.println(label.getDisplayName() + " has no available nodes.");
		return  hasNodeOnlineAndAvailable;
	}

	private boolean hasNodeOnlineAndAvailable(PrintStream logger, Label label, Set<Node> nodes) {
		if (nodes.size() == 0)
			return false;
		logger.append(label.getDisplayName()+": label has nodes\n");
		for (Node node : nodes) {
			String nodeName = node.getDisplayName();
			logger.println("Checking node : " + nodeName);
			Computer c = node.toComputer();
			if (c.isOffline() || c.isConnecting()) {
				continue;
			}
			if (isNodeOnlineAndAvailable(c) && hasAvailableExecutor(c))
				return true;
			
			logger.append(nodeName + " node not available." );
		}
		return false;
	}

	private boolean hasAvailableExecutor(Computer c) {
		final List<Executor> executors = c.getExecutors();
		for (Executor executor : executors) {
			if (executor.isIdle()) 
				return true;
		}
		return false;
	}

	private boolean isNodeOnlineAndAvailable(Computer c) {
		return (c.isOnline() || c.isConnecting()) && c.isAcceptingTasks();
	}

	@Extension
	public static class DescriptorImpl extends AmazonEC2Cloud.DescriptorImpl {
	    @Override
		public String getDisplayName() {
	        return "EC2 Axis Amazon Cloud";
	    }
	}

	public KeyPair getKeyPair(AmazonEC2 ec2) throws AmazonClientException, IOException {
		return ec2PrivateKey.find(ec2);
	}
}
