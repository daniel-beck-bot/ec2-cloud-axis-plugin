package hudson.plugins.ec2;

import hudson.model.Hudson;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.lang.time.StopWatch;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.SpotInstanceRequest;
import com.trilead.ssh2.ChannelCondition;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.ServerHostKeyVerifier;
import com.trilead.ssh2.Session;

final class SpotRequestConnectSupervisor implements Runnable {
	private final List<SpotInstanceRequest> reqInstances;
	private final List<EC2AbstractSlave> spotSlaves;
	private final AmazonEC2 ec2;
	private String remoteAdmin;
	private char [] privateKey;
	private PrintStream logger;

	
	public static void start(PrintStream logger, List<SpotInstanceRequest> reqInstances, List<EC2AbstractSlave> spotSlaves, AmazonEC2 ec2, char [] privateKey, String remoteAdmin) {
		new Thread(new SpotRequestConnectSupervisor(logger, reqInstances, spotSlaves, ec2, privateKey, remoteAdmin)).start();
	}

	private SpotRequestConnectSupervisor(
			PrintStream logger, List<SpotInstanceRequest> reqInstances,
			List<EC2AbstractSlave> spotSlaves, 
			AmazonEC2 ec2,
			char [] privateKey, 
			String remoteAdmin) {
		this.logger = logger;
		this.reqInstances = reqInstances;
		this.spotSlaves = spotSlaves;
		this.ec2 = ec2;
		this.privateKey = privateKey;
		this.remoteAdmin = remoteAdmin;
	}

	@Override 
	public void run() {
		List<String> spotInstanceRequestIds = new ArrayList<String>();
		for (SpotInstanceRequest req : reqInstances) {
			spotInstanceRequestIds.add(req.getSpotInstanceRequestId());
		}
		LinkedList<String> remainingSlaves = new LinkedList<String>();
		for (EC2AbstractSlave ec2AbstractSlave : spotSlaves) {
			logger.println("Adding slave " + ec2AbstractSlave.getNodeName() + " to be associated with an instance");
			remainingSlaves.add(ec2AbstractSlave.getNodeName());
		}
		
		do {
			DescribeSpotInstanceRequestsRequest describeRequest = new DescribeSpotInstanceRequestsRequest();
			describeRequest.setSpotInstanceRequestIds(spotInstanceRequestIds);
	
			try {
				logger.println("Checking whether spot requests have been fulfilled");
				DescribeSpotInstanceRequestsResult describeResult = ec2.describeSpotInstanceRequests(describeRequest);
				List<SpotInstanceRequest> describeResponses = describeResult.getSpotInstanceRequests();
	
				List<String> fulfilled = new LinkedList<String>();
				for (SpotInstanceRequest describeResponse : describeResponses) {
					if (describeResponse.getState().equals("open")) {
						continue;
					}
					logger.println("Request fulfilled: " + 
							describeResponse.getSpotInstanceRequestId() +
							" Instance id : " + describeResponse.getInstanceId()
							);
					fulfilled.add(describeResponse.getInstanceId());
					spotInstanceRequestIds.remove(describeResponse.getSpotInstanceRequestId());
				}
				makeInstanceConnectBackOnJenkins(fulfilled, remainingSlaves);
			} catch (AmazonServiceException e) {
				e.printStackTrace();
			} catch (AmazonClientException e) {
				throw new RuntimeException(e);
			} catch (IOException e) {
				throw new RuntimeException(e);
			} catch(RuntimeException e) {
				e.printStackTrace(logger);
				throw e;
			}
			
	
			try {
				// Sleep for 60 seconds.
				Thread.sleep(60 * 1000);
			} catch (Exception e) {
				// Do nothing because it woke up early.
			}
		} while (spotInstanceRequestIds.size()>0);
	}

	private void makeInstanceConnectBackOnJenkins(List<String> fulfilledInstanceIds, LinkedList<String> remainingSlaves) 
			throws AmazonClientException, IOException {
		if (fulfilledInstanceIds.size() == 0)
			return;
		if (remainingSlaves.size() == 0) {
			logger.println("No slaves remaining!");
		}
		
		String jenkinsUrl = Hudson.getInstance().getRootUrl();
		DescribeInstancesResult describeInstances = ec2.describeInstances(new DescribeInstancesRequest().withInstanceIds(fulfilledInstanceIds) );
		List<Instance> instances = describeInstances.getReservations().get(0).getInstances();
		for (Instance instance : instances) {
			String privateIpAddress = instance.getPrivateIpAddress();
			
			boolean success;
			int timeoutInMinutes = 20;
			int timeout = 1000 * 60 * timeoutInMinutes;
			long maxWait = System.currentTimeMillis() + timeout;
			StopWatch stopwatch = new StopWatch();
			stopwatch.start();
			do{
				success = tryToLaunchSlave(remainingSlaves, jenkinsUrl, privateIpAddress);
				
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					return;
				}
			}while(!success && System.currentTimeMillis() < maxWait );
			stopwatch.stop();
			if(!success){
				logger.println("ERROR! Could not connect to "+privateIpAddress);
			}
			else {
				logger.println("It took" + stopwatch.getTime() + " ms to connect to the instance");
			}
		}
	}

	private boolean tryToLaunchSlave(LinkedList<String> remainingSlaves,String jenkinsUrl, String privateIpAddress) {
		try {
			if (remainingSlaves.size() == 0) {
				logger.println("No slaves remaining to associate!");
				return false;
			}
			logger.println("Trying to connect to "+privateIpAddress);
			Connection sshConnection = new Connection(privateIpAddress);
			sshConnection.connect(new ServerHostKeyVerifier() {
		        public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey) throws Exception {
		            return true;
		        }
		    });
			if (sshConnection.authenticateWithPublicKey(remoteAdmin, privateKey, "")) {
				String slaveToAssociate = remainingSlaves.peek();
				logger.println("Will associate slave " + slaveToAssociate + " with instance with ip " + privateIpAddress);
				
				try {
					Session openSession = sshConnection.openSession();
					String wgetCmd = "wget " + jenkinsUrl + "jnlpJars/slave.jar -O slave.jar";
					String encodedSlaveToAssociate = slaveToAssociate.replace(" ", "%20");
					String slaveLaunch = "java -jar slave.jar -jnlpUrl \"" + jenkinsUrl + "computer/" + encodedSlaveToAssociate + "/slave-agent.jnlp\"";
					String slaveLaunchCmd = "nohup " +slaveLaunch + " > slave.log 2> slave.err </dev/null &";
					
					execCommandAndWaitForCompletion(openSession, wgetCmd + " && " + slaveLaunchCmd);
					openSession.close();
					logger.println("Successfully connected to "+privateIpAddress);
					remainingSlaves.pop();
					return true; 
				}catch(Exception e) {
					return false;
				}
			}
			else {
				String message = "Could not connect with user " + remoteAdmin + " on " + privateIpAddress;
				logger.println(message);
				throw new RuntimeException(message);
			}
		}catch(Exception e) {
			return false;
		}
	}

	private void execCommandAndWaitForCompletion(Session openSession, String cmd) throws IOException, InterruptedException {
		int timeoutForCommand = 60 * 1000 * 5;
		openSession.execCommand(cmd);
		openSession.waitForCondition(ChannelCondition.EXIT_STATUS, timeoutForCommand);
		Integer exitStatus = openSession.getExitStatus();
		if(exitStatus != 0){
			logger.println("Command failed: " + cmd);
			throw new RuntimeException("Command failed: " + cmd);
		}
	}
}