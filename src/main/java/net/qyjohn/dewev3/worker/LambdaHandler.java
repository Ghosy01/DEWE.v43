package net.qyjohn.dewev3.worker;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.services.lambda.runtime.*; 
import com.amazonaws.services.lambda.runtime.events.*;
import com.amazonaws.services.s3.*;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.services.kinesis.*;
import com.amazonaws.services.kinesis.model.*;
import org.dom4j.*;
import org.dom4j.io.SAXReader;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;

public class LambdaHandler
{
	// Common components
	public AmazonS3Client s3Client;
	public AmazonKinesisClient kinesisClient;
	public String workflow, bucket, prefix, jobId, jobName, command;
	// Cached binaries, input and output files
	public String tempDir;
	public HashMap<String, Boolean> cachedFiles;

	// Logging
	final static Logger logger = Logger.getLogger(LambdaHandler.class);

	/**
	 *
	 * Constructor for Lambda function. 
	 * In this case, the long running job stream is not needed.
	 *
	 */
	 
	public LambdaHandler()
	{
		s3Client = new AmazonS3Client();
		kinesisClient = new AmazonKinesisClient();
	}

	/**
	 *
	 * The only purpose of this handler is to clean up the /tmp disk space.
	 *
	 */

	public void cleanUpHandler(KinesisEvent event)
	{
		runCommand("rm -Rf /tmp/*", "/tmp");
		runCommand("df -h", "/tmp");		
	}
	
	public void probeHandler(KinesisEvent event)
	{
		runCommand("nproc", "/tmp");
		runCommand("free", "/tmp");
	}

	/**
	 *
	 * When the job handler runs on an EC2 instance, it is a function triggered by Lambda.
	 *
	 */

	public void serialHandler(KinesisEvent event)
	{
		// Create temporary execution folder
		tempDir = "/tmp/" + UUID.randomUUID().toString();
		runCommand("mkdir -p " + tempDir, "/tmp");
		
		// Create a new HashMap for cached files
		cachedFiles = new HashMap<String, Boolean>();
		
		for(KinesisEvent.KinesisEventRecord rec : event.getRecords())
		{
			try
			{
				// Basic workflow information
				String jobXML = new String(rec.getKinesis().getData().array());
				executeJob(jobXML);
			} catch (Exception e)
			{
				System.out.println(e.getMessage());
				e.printStackTrace();
			}
		}
		
		// Clean up temporary execution folder
		runCommand("rm -Rf " + tempDir, "/tmp");
	}

	public void parallelHandler(KinesisEvent event)
	{
		// Create temporary execution folder
		tempDir = "/tmp/" + UUID.randomUUID().toString();
		runCommand("mkdir -p " + tempDir, "/tmp");
		
		// Create the data structure to store all job id's, commands, binaries, input and output files
		List<KinesisEvent.KinesisEventRecord> records = event.getRecords();
		List<String> ids      = new LinkedList<String>();
		List<String> commands = new LinkedList<String>();
		List<String> binFiles = new LinkedList<String>();
		List<String> inFiles  = new LinkedList<String>();
		List<String> outFiles = new LinkedList<String>();	
			
		// Extract infomation about all job id's, commands, binaries, input and output files
		for(KinesisEvent.KinesisEventRecord rec : records)
		{
			try
			{
				// Basic workflow information
				String jobXML = new String(rec.getKinesis().getData().array());
				Element job = DocumentHelper.parseText(jobXML).getRootElement();
				workflow = job.attributeValue("workflow");
				bucket   = job.attributeValue("bucket");
				prefix   = job.attributeValue("prefix");
				ids.add(job.attributeValue("id"));
				commands.add(job.attributeValue("command"));
				
				// Binaries, input and output files
				StringTokenizer st;
				st = new StringTokenizer(job.attribute("binFiles").getValue());
				while (st.hasMoreTokens()) 
				{
					String f = st.nextToken();
					if (!binFiles.contains(f))
					{
						binFiles.add(f);
					}
				}
				st = new StringTokenizer(job.attribute("inFiles").getValue());
				while (st.hasMoreTokens()) 
				{
					String f = st.nextToken();
					if (!inFiles.contains(f))
					{
						inFiles.add(f);
					}
				}
				st = new StringTokenizer(job.attribute("outFiles").getValue());
				while (st.hasMoreTokens()) 
				{
					String f = st.nextToken();
					if (!outFiles.contains(f))
					{
						outFiles.add(f);
					}
				}
			} catch (Exception e)
			{
				System.out.println(e.getMessage());
				e.printStackTrace();
			}
		}
		
		// Parallel download of binaries using multiple threads
		if (!binFiles.isEmpty())
		{
			try
			{
				Downloader downloader[] = new Downloader[binFiles.size()];
				for (int i=0; i<binFiles.size(); i++)
				{
					downloader[i] = new Downloader("bin", binFiles.get(i));
					downloader[i].start();
				}
				for (int i=0; i<binFiles.size(); i++)
				{
					downloader[i].join();
				}
			} catch (Exception e)
			{
				System.out.println(e.getMessage());
				e.printStackTrace();
			}
		}		
		
		// Parallel donwload of input files using multiple threads
		if (!inFiles.isEmpty())
		{
			try
			{
				Downloader downloader[] = new Downloader[inFiles.size()];
				for (int i=0; i<inFiles.size(); i++)
				{
					downloader[i] = new Downloader("workdir", inFiles.get(i));
					downloader[i].start();
				}
				for (int i=0; i<inFiles.size(); i++)
				{
					downloader[i].join();
				}
			} catch (Exception e)
			{
				System.out.println(e.getMessage());
				e.printStackTrace();
			}
		}		
		
		// "chmod +x" for all binaries
		String cmd = "chmod +x";
		for (String c : binFiles)
		{
			cmd = cmd + " " + c;
		}
		runCommand(cmd, tempDir);
		
		// Parallel execution of commands using multiple threads
		try
		{
			if (!commands.isEmpty())
			{
				Executor executor[] = new Executor[commands.size()];
				for (int i=0; i<commands.size(); i++)
				{
					executor[i] = new Executor(tempDir + "/" + commands.get(i));
					executor[i].start();
				}
				for (int i=0; i<commands.size(); i++)
				{
					executor[i].join();
				}
			}
		} catch (Exception e)
		{
			System.out.println(e.getMessage());
			e.printStackTrace();
		}
		
		// Parallel upload of output files using multiple threads
		try
		{
			if (!outFiles.isEmpty())
			{
				Uploader uploader[] = new Uploader[outFiles.size()];
				for (int i=0; i<outFiles.size(); i++)
				{
					uploader[i] = new Uploader(outFiles.get(i));
					uploader[i].start();
				}
				for (int i=0; i<outFiles.size(); i++)
				{
					uploader[i].join();
				}
			}
		} catch (Exception e)
		{
			System.out.println(e.getMessage());
			e.printStackTrace();
		}
		
		// Parallel ACK of all jobs using multiple threads
		try
		{
			if (!ids.isEmpty())
			{
				Acker acker[] = new Acker[ids.size()];
				for (int i=0; i<ids.size(); i++)
				{
					acker[i] = new Acker(ids.get(i));
					acker[i].start();
				}
				for (int i=0; i<ids.size(); i++)
				{
					acker[i].join();
				}
			}
		} catch (Exception e)
		{
			System.out.println(e.getMessage());
			e.printStackTrace();
		}
		
		// Clean up temporary execution folder
		runCommand("rm -Rf " + tempDir, "/tmp");
	}

	public void executeJob(String jobXML)
	{
		try
		{
			Element job = DocumentHelper.parseText(jobXML).getRootElement();
			workflow = job.attributeValue("workflow");
			bucket   = job.attributeValue("bucket");
			prefix   = job.attributeValue("prefix");
			jobId    = job.attributeValue("id");
			jobName  = job.attributeValue("name");
			command  = job.attributeValue("command");

			logger.info(jobId + "\t" + jobName);
			logger.debug(jobXML);

			// Download binary and input files
			StringTokenizer st;
			st = new StringTokenizer(job.attribute("binFiles").getValue());
			while (st.hasMoreTokens()) 
			{
				String f = st.nextToken();
				download(1, f);
				runCommand("chmod u+x " + tempDir + "/" + f, tempDir);
			}
			st = new StringTokenizer(job.attribute("inFiles").getValue());
			while (st.hasMoreTokens()) 
			{
				String f = st.nextToken();
				download(2, f);
			}

			// Execute the command and wait for it to complete
			runCommand(tempDir + "/" + command, tempDir);

			// Upload output files
			st = new StringTokenizer(job.attribute("outFiles").getValue());
			while (st.hasMoreTokens()) 
			{
				String f = st.nextToken();
				upload(f);
			}
		} catch (Exception e)
		{
			System.out.println(e.getMessage());
			e.printStackTrace();
		}
			// Acknowledge the job to be completed
			ackJob(workflow, jobId);

	}
	
	/**
	 *
	 * Download binary and input data from S3 to the execution folder.
	 *
	 */
	 
/*	public void download(int type, String dir, String filename)
	{
		String key=null, outfile = null;
		if (type==1)	// Binary
		{
			key = prefix + "/bin/" + filename;
			outfile = dir + "/" + filename;
		}
		else	// Data
		{
			key = prefix + "/workdir/" + filename;
			outfile = dir + "/" + filename;
		}
		
		try
		{
			logger.debug("Downloading " + outfile);
			S3Object object = s3Client.getObject(new GetObjectRequest(bucket, key));
			InputStream in = object.getObjectContent();
			OutputStream out = new FileOutputStream(outfile);
			IOUtils.copy(in, out);
			in.close();
			out.close();
		} catch (Exception e)
		{
			System.out.println(e.getMessage());
			e.printStackTrace();
		}		
	}
*/

	public void download(int type, String filename) throws Exception
	{
		if (cachedFiles.get(filename) == null)
		{
			cachedFiles.put(filename, new Boolean(false));
			String key=null, outfile = null;
			if (type==1)	// Binary
			{
				key = prefix + "/bin/" + filename;
				outfile = tempDir + "/" + filename;
			}
			else	// Data
			{
				key = prefix + "/workdir/" + filename;
				outfile = tempDir + "/" + filename;
			}
		
			logger.debug("Downloading " + outfile);
			S3Object object = s3Client.getObject(new GetObjectRequest(bucket, key));
			InputStream in = object.getObjectContent();
			OutputStream out = new FileOutputStream(outfile);
			IOUtils.copy(in, out);
			in.close();
			out.close();
			cachedFiles.put(filename, new Boolean(true));
		}
		else
		{
			while (cachedFiles.get(filename).booleanValue() == false)
			{
				try
				{
					Thread.sleep(100);
				} catch (Exception e)
				{
					System.out.println(e.getMessage());
					e.printStackTrace();
				}
			}
		}			
	}


	/**
	 *
	 * Upload output data to S3
	 *
	 */
	 
/*	public void upload(String dir, String filename)
	{
		String key  = prefix + "/workdir/" + filename;
		String file = dir + "/" + filename;

		try
		{
			logger.debug("Uploading " + file);
			s3Client.putObject(new PutObjectRequest(bucket, key, new File(file)));
		} catch (Exception e)
		{
			System.out.println(e.getMessage());
			e.printStackTrace();
		}
	}
*/

	public void upload(String filename) throws Exception 
	{
		try
		{
			cachedFiles.put(filename, new Boolean(false));
			String key  = prefix + "/workdir/" + filename;
			String file = tempDir + "/" + filename;

			logger.debug("Uploading " + file);
			s3Client.putObject(new PutObjectRequest(bucket, key, new File(file)));
			cachedFiles.put(filename, new Boolean(true));			
		} catch (Exception e)
		{
			System.out.println(e.getMessage());
			e.printStackTrace();
		}
	}
	
	/**
	 *
	 * ACK to the workflow scheduler that the job is now completed
	 *
	 */
	 
	public void ackJob(String ackStream, String id)
	{
		byte[] bytes = id.getBytes();
		PutRecordRequest putRecord = new PutRecordRequest();
		putRecord.setStreamName(ackStream);
		putRecord.setPartitionKey(UUID.randomUUID().toString());
		putRecord.setData(ByteBuffer.wrap(bytes));

		try 
		{
			kinesisClient.putRecord(putRecord);
		} catch (Exception e) 
		{
			System.out.println(e.getMessage());
			e.printStackTrace();	
		}
	}
	
	
	/**
	 *
	 * Run a command 
	 *
	 */
	 
	public void runCommand(String command, String dir)
	{
		try
		{
			logger.debug(command);

			String env_path = "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:" + dir;
			String env_lib = "LD_LIBRARY_PATH=$LD_LIBRARY_PATH:" + dir;
			String[] env = {env_path, env_lib};
			Process p = Runtime.getRuntime().exec(command, env, new File(dir));
			BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
			String result = "";
			String line;
			while ((line = in.readLine()) != null) 
			{
				result = result + line + "\n";
			}       
			in.close();
			p.waitFor();
			logger.debug(result);
		} catch (Exception e) 
		{
			logger.error(e.getMessage());
			logger.error(e.getStackTrace());
		}
	}
	
	class Downloader extends Thread
	{
		String folder, filename;

		public Downloader(String folder, String filename)
		{
			this.folder = folder;
			this.filename = filename;
		}

		public void run()
		{
			try
			{
				String key     = prefix + "/" + folder + "/" + filename;
				String outfile = tempDir + "/" + filename;
		
				logger.debug("Downloading " + key + " to " + outfile);
				S3Object object = s3Client.getObject(new GetObjectRequest(bucket, key));
				InputStream in = object.getObjectContent();
				OutputStream out = new FileOutputStream(outfile);
				IOUtils.copy(in, out);
				in.close();
				out.close();
			} catch (Exception e)
			{
				System.out.println(e.getMessage());
				e.printStackTrace();
			}
		}
	}
 
	class Uploader extends Thread
	{
		public String filename;

		public Uploader(String filename)
		{
			this.filename = filename;
		}

		public void run()
		{
			try
			{
				String key  = prefix + "/workdir/" + filename;
				String file = tempDir + "/" + filename;

				logger.debug("Uploading " + file);
				s3Client.putObject(new PutObjectRequest(bucket, key, new File(file)));
			} catch (Exception e)
			{
				System.out.println(e.getMessage());
				e.printStackTrace();
			}
		}
	}	
	
	class Executor extends Thread
	{
		public String cmd, dir;
		
		public Executor(String cmd)
		{
			this.cmd = cmd;
		}
		
		public void run()
		{
			try
			{
				runCommand(cmd, tempDir);
			} catch (Exception e)
			{
				System.out.println(e.getMessage());
				e.printStackTrace();
			}			
		}
	}
	class Acker extends Thread
	{
		public String id;
		
		public Acker(String id)
		{
			this.id = id;
		}
		
		public void run()
		{
			try
			{
				ackJob(workflow, id);
			} catch (Exception e)
			{
				System.out.println(e.getMessage());
				e.printStackTrace();
			}
		}
	}
}
