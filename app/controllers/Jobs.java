package controllers;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.management.RuntimeErrorException;

import models.Job;
import models.Notification;
import models.NotificationConnection;
import models.Setting;
import models.Upload;
import models.User;

import org.daisy.pipeline.client.Pipeline2WS;
import org.daisy.pipeline.client.Pipeline2WSException;
import org.daisy.pipeline.client.Pipeline2WSResponse;
import org.daisy.pipeline.client.models.Script;
import org.daisy.pipeline.client.models.script.Argument;
import org.daisy.pipeline.client.models.script.arguments.ArgFile;
import org.daisy.pipeline.client.models.script.arguments.ArgFiles;
import org.w3c.dom.Node;

import play.Logger;
import play.db.ebean.Transactional;
import play.libs.XPath;
import play.mvc.*;

public class Jobs extends Controller {

	public static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	public static Result getJobs() {
		if (FirstUse.isFirstUse())
			return redirect(routes.FirstUse.getFirstUse());
		
		User user = User.authenticate(request(), session());
		if (user == null || (user.id < 0 && !"true".equals(Setting.get("users.guest.shareJobs"))))
			return redirect(routes.Login.login());
		
		Pipeline2WSResponse jobs;
		List<Node> jobNodes;
		try {
			jobs = org.daisy.pipeline.client.Jobs.get(Setting.get("dp2ws.endpoint"), Setting.get("dp2ws.authid"), Setting.get("dp2ws.secret"));
			
			if (jobs.status != 200) {
				return Application.error(jobs.status, jobs.statusName, jobs.statusDescription, jobs.asText());
			}
			
			jobNodes = XPath.selectNodes("//d:job", jobs.asXml(), Pipeline2WS.ns);
			
		} catch (Pipeline2WSException e) {
			Logger.error(e.getMessage(), e);
			return Application.error(500, "Sorry, something unexpected occured", "A problem occured while communicating with the Pipeline 2 framework", e.getMessage());
		}
		
		List<Job> jobList = new ArrayList<Job>();
		for (Node jobNode : jobNodes) {
			
			Job job = Job.findById(XPath.selectText("@id", jobNode, Pipeline2WS.ns));
			if (job == null) {
				Logger.warn("No job with id "+XPath.selectText("@id", jobNode, Pipeline2WS.ns)+" was found.");
			} else {
				job.href = XPath.selectText("@href", jobNode, Pipeline2WS.ns);
				job.status = XPath.selectText("@status", jobNode, Pipeline2WS.ns);
				if (user.admin || user.id >= 0 && user.id.equals(job.user) || user.id < 0 && job.user < 0 && "true".equals(Setting.get("users.guest.shareJobs")))
					jobList.add(job);
			}
		}
		
		Collections.sort(jobList);
		Collections.reverse(jobList);
		if (user.admin)
			flash("showOwner", "true");
		
		Long browserId = new Random().nextLong();
		NotificationConnection.createBrowserIfAbsent(user.id, browserId);
		Logger.debug("Browser: user #"+user.id+" opened browser window #"+browserId);
		flash("browserId",""+browserId);
		return ok(views.html.Jobs.getJobs.render(jobList));
	}
	
	public static Result getJob(String id) {
		if (FirstUse.isFirstUse())
			return redirect(routes.FirstUse.getFirstUse());
		
		User user = User.authenticate(request(), session());
		if (user == null)
			return redirect(routes.Login.login());
		
		Pipeline2WSResponse response;
		org.daisy.pipeline.client.models.Job job;
		try {
			response = org.daisy.pipeline.client.Jobs.get(Setting.get("dp2ws.endpoint"), Setting.get("dp2ws.authid"), Setting.get("dp2ws.secret"), id, null);
			
			if (response.status != 200 && response.status != 201) {
				return Application.error(response.status, response.statusName, response.statusDescription, response.asText());
			}
			
			job = new org.daisy.pipeline.client.models.Job(response.asXml());
			Logger.debug(utils.XML.toString(response.asXml()));
			
		} catch (Pipeline2WSException e) {
			Logger.error(e.getMessage(), e);
			return Application.error(500, "Sorry, something unexpected occured", "A problem occured while communicating with the Pipeline 2 framework", e.getMessage());
		}
		
		Job webuiJob = Job.findById(job.id);
		if (webuiJob == null) {
			Logger.debug("Job #"+job.id+" was not found.");
			return notFound("Sorry; something seems to have gone wrong. The job was not found.");
		}
		if (!(	user.admin
			||	webuiJob.user.equals(user.id)
			||	webuiJob.user < 0 && user.id < 0 && "true".equals(Setting.get("users.guest.shareJobs"))
				))
			return forbidden("You are not allowed to view this job.");
		
		if (!Job.lastMessageSequence.containsKey(job.id) && job.messages.size() > 0) {
			Collections.sort(job.messages);
			Job.lastMessageSequence.put(job.id, job.messages.get(job.messages.size()-1).sequence);
		}
		if (!Job.lastStatus.containsKey(job.id)) {
			Job.lastStatus.put(job.id, job.status);
		}
		
		Long browserId = new Random().nextLong();
		NotificationConnection.createBrowserIfAbsent(user.id, browserId);
		Logger.debug("Browser: user #"+user.id+" opened browser window #"+browserId);
		flash("browserId",""+browserId);
		return ok(views.html.Jobs.getJob.render(job, webuiJob));
	}

	public static Result getResult(String id) {
		if (FirstUse.isFirstUse())
			return redirect(routes.FirstUse.getFirstUse());
		
		User user = User.authenticate(request(), session());
		if (user == null)
			return redirect(routes.Login.login());
		
		Job webuiJob = Job.findById(id);
		if (webuiJob == null) {
			Logger.debug("Job #"+id+" was not found.");
			return notFound("Sorry; something seems to have gone wrong. The job was not found.");
		}
		if (!(	user.admin
				||	webuiJob.user.equals(user.id)
				||	webuiJob.user < 0 && user.id < 0 && "true".equals(Setting.get("users.guest.shareJobs"))
					))
				return forbidden("You are not allowed to view this job.");
		
		try {
			Pipeline2WSResponse result = org.daisy.pipeline.client.Jobs.getResult(Setting.get("dp2ws.endpoint"), Setting.get("dp2ws.authid"), Setting.get("dp2ws.secret"), id);
			
			// TODO: check content type of incoming stream? Implement result.getContentType() ?
			
			response().setHeader("Content-Disposition", "attachment; filename=\"result-"+id+".zip\"");
			response().setContentType("application/zip");

			return ok(result.asStream());
			
		} catch (Pipeline2WSException e) {
			Logger.error(e.getMessage(), e);
			return Application.error(500, "Sorry, something unexpected occured", "A problem occured while communicating with the Pipeline 2 framework", e.getMessage());
		}
	}

	public static Result getLog(final String id) {
		if (FirstUse.isFirstUse())
			return redirect(routes.FirstUse.getFirstUse());
		
		User user = User.authenticate(request(), session());
		if (user == null)
			return redirect(routes.Login.login());
		
		Job webuiJob = Job.findById(id);
		if (webuiJob == null) {
			Logger.debug("Job #"+id+" was not found.");
			return notFound("Sorry; something seems to have gone wrong. The job was not found.");
		}
		if (!(	user.admin
				||	webuiJob.user.equals(user.id)
				||	webuiJob.user < 0 && user.id < 0 && "true".equals(Setting.get("users.guest.shareJobs"))
					))
				return forbidden("You are not allowed to view this job.");
		
		Pipeline2WSResponse jobLog;
		try {
			jobLog = org.daisy.pipeline.client.Jobs.getLog(Setting.get("dp2ws.endpoint"), Setting.get("dp2ws.authid"), Setting.get("dp2ws.secret"), id);
	
			if (jobLog.status != 200 && jobLog.status != 201 && jobLog.status != 204) {
				return Application.error(jobLog.status, jobLog.statusName, jobLog.statusDescription, jobLog.asText());
			}
	
			if (jobLog.status == 204) {
				return ok(views.html.Jobs.emptyLog.render(id));
	
			} else {
				String[] lines = jobLog.asText().split("\n");
				return ok(views.html.Jobs.getLog.render(id, Arrays.asList(lines)));
			}
			
		} catch (Pipeline2WSException e) {
			Logger.error(e.getMessage(), e);
			return Application.error(500, "Sorry, something unexpected occured", "A problem occured while communicating with the Pipeline 2 framework", e.getMessage());
		}
	}

    /*
     * @Transactional - annotation gives:
     * 
     * Execution Exception
     * [PersistenceException: The default EbeanServer has not been defined? This is normally set via the ebean.datasource.default property. Otherwise it should be registered programatically via registerServer()]
     * 
     * ! @6blglm0lm - Internal server error, for request [POST /jobs] ->
	 *
	 *	play.core.ActionInvoker$$anonfun$receive$1$$anon$1: Execution exception [[PersistenceException: The default EbeanServer has not been defined? This is normally set via the ebean.datasource.default property. Otherwise it should be registered programatically via registerServer()]]
	 *		at play.core.ActionInvoker$$anonfun$receive$1.apply(Invoker.scala:134) [play_2.9.1.jar:2.0.3]
	 *		at play.core.ActionInvoker$$anonfun$receive$1.apply(Invoker.scala:115) [play_2.9.1.jar:2.0.3]
	 *		at akka.actor.Actor$class.apply(Actor.scala:318) [akka-actor.jar:2.0.2]
	 *		at play.core.ActionInvoker.apply(Invoker.scala:113) [play_2.9.1.jar:2.0.3]
	 *		at akka.actor.ActorCell.invoke(ActorCell.scala:626) [akka-actor.jar:2.0.2]
	 *		at akka.dispatch.Mailbox.processMailbox(Mailbox.scala:197) [akka-actor.jar:2.0.2]
	 *	Caused by: javax.persistence.PersistenceException: The default EbeanServer has not been defined? This is normally set via the ebean.datasource.default property. Otherwise it should be registered programatically via registerServer()
	 *		at com.avaje.ebean.Ebean$ServerManager.getPrimaryServer(Ebean.java:209) ~[ebean.jar:na]
	 *		at com.avaje.ebean.Ebean$ServerManager.access$300(Ebean.java:159) ~[ebean.jar:na]
	 *		at com.avaje.ebean.Ebean.execute(Ebean.java:1348) ~[ebean.jar:na]
	 *		at play.db.ebean.TransactionalAction.call(TransactionalAction.java:14) ~[play_2.9.1.jar:2.0.3]
	 *		at play.core.j.JavaAction$class.apply(JavaAction.scala:74) ~[play_2.9.1.jar:2.0.3]
	 *		at play.core.Router$HandlerInvoker$$anon$4$$anon$1.apply(Router.scala:1085) ~[play_2.9.1.jar:2.0.3]
     */
	public static Result postJob() {
		if (FirstUse.isFirstUse())
			return redirect(routes.FirstUse.getFirstUse());

		User user = User.authenticate(request(), session());
		if (user == null)
			return redirect(routes.Login.login());
		
		Logger.debug("------------------------------ Posting job... ------------------------------");

		Map<String, String[]> params = request().body().asFormUrlEncoded();
		if (params == null) {
			return Application.error(500, "Internal Server Error", "Could not read form data", request().body().asText());
		}

		String id = params.get("id")[0];

		// Get a description of the script from Pipeline 2 Web Service
		Pipeline2WSResponse scriptResponse;
		Script script;
		try {
			scriptResponse = org.daisy.pipeline.client.Scripts.get(Setting.get("dp2ws.endpoint"), Setting.get("dp2ws.authid"), Setting.get("dp2ws.secret"), id);
			if (scriptResponse.status != 200) { return Application.error(scriptResponse.status, scriptResponse.statusName, scriptResponse.statusDescription, scriptResponse.asText()); }
			script = new Script(scriptResponse);
			
		} catch (Pipeline2WSException e) {
			Logger.error(e.getMessage(), e);
			return Application.error(500, "Sorry, something unexpected occured", "A problem occured while communicating with the Pipeline 2 framework", e.getMessage());
		}
		
		// Parse and validate the submitted form (also create any necessary output directories in case of local mode)
		Scripts.ScriptForm scriptForm = new Scripts.ScriptForm(user.id, script, params);
		String timeString = new Date().getTime()+"";
		for (Argument arg : script.arguments) {
			if ("result".equals(arg.output)) {
				File href = new File(Setting.get("dp2ws.resultDir")+timeString+"/"+arg.kind+"-"+arg.name+"/");
				href.mkdirs();
//				script.arguments.set(script.arguments.indexOf(arg), new ArgFile(arg, href.toURI().toString()));
				
			} else if ("temp".equals(arg.output)) {
				File href = new File(Setting.get("dp2ws.tempDir")+timeString+"/"+arg.kind+"-"+arg.name+"/");
				href.mkdirs();
//				script.arguments.set(script.arguments.indexOf(arg), new ArgFile(arg, href.toURI().toString()));
			}
		}
		scriptForm.validate();

		File contextZipFile = null;

		if (scriptForm.uploads.size() > 0) {

			// ---------- See if there's an existing ZIP we can use as the context ----------
			//	models.Upload contextZipUpload = null;
			//	Long biggestZipSoFar = 0L;
			//	
			//	for (Long uploadId : uploads.keySet()) {
			//		Upload upload = uploads.get(uploadId);
			//		if (upload.isZip()) {
			//			File file = upload.getFile();
			//			if (file.length() > biggestZipSoFar) {
			//				biggestZipSoFar = file.length();
			//				contextZipUpload = upload;
			//			}
			//		}
			//	}
			//	
			//	if (contextZipUpload == null)
			//		Logger.debug("There's no ZIP files available to use as context");
			//	else
			//		Logger.debug("The ZIP file '"+contextZipUpload.getFile().getAbsolutePath()+"' is the biggest ZIP file available and will be used as context");

			// ---------- Create a temporary directory ("the context") ----------
			File contextDir = null;
			try {
				contextDir = File.createTempFile("jobContext", "");

				if (!(contextDir.delete())) {
					Logger.error("Could not delete contextDir file: " + contextDir.getAbsolutePath());

				} if (!(contextDir.mkdir())) {
					Logger.error("Could not create contextDir directory: " + contextDir.getAbsolutePath());
				}

			} catch (IOException e) {
				Logger.error("Could not create temporary file (context directory): "+e.getMessage(), e);
				return internalServerError("Could not create temporary file (context directory)");
			}

			Logger.debug("Created context directory: "+contextDir.getAbsolutePath());

			// ---------- Copy or unzip all uploads to a common directory ----------
			Logger.debug("number of uploads: "+scriptForm.uploads.size());
			for (Long uploadId : scriptForm.uploads.keySet()) {
				Upload upload = scriptForm.uploads.get(uploadId);
				if (upload.isZip()) {
					//					if (contextZipUpload != null && contextZipUpload.id == upload.id) {
					//						Logger.debug("not unzipping context zip ("+upload.getFile().getAbsolutePath()+")");
					//						continue;
					//					}
					Logger.debug("unzipping "+upload.getFile()+" to contextDir");
					try {
						utils.Files.unzip(upload.getFile(), contextDir);
					} catch (IOException e) {
						Logger.error("Unable to unzip files into context directory.", e);
						return Application.error(500, "Internal Server Error", "Unable to unzip uploaded ZIP file", "");
					}
				} else {
					File from = upload.getFile();
					File to = new File(contextDir, from.getName());
					Logger.debug("copying "+from+" to "+to);
					try {
						utils.Files.copy(from, to); // We could do file.renameTo here to move it instead of making a copy, but copying makes it easier in case we need to re-run a job
					} catch (IOException e) {
						Logger.error("Unable to copy files to context directory.", e);
						throw new RuntimeErrorException(new Error(e), "Unable to copy files to context directory.");
					}
				}
			}
			
			if ("true".equals(Setting.get("dp2ws.sameFilesystem"))) {
				Logger.debug("Running the Web UI and fwk on the same filesystem, no need to ZIP files...");
				for (Argument arg : script.arguments) {
					if (arg.output != null) {
						Logger.debug(arg.name+" is output; don't resolve URI");
						continue;
					}
					if (arg instanceof ArgFile) {
						Logger.debug(arg.name+" is file; resolve URI");
						((ArgFile)arg).href = contextDir.toURI().resolve(((ArgFile)arg).href).toString();
					}
					if (arg instanceof ArgFiles) {
						Logger.debug(arg.name+" is files; resolve URIs");
						List<String> hrefs = ((ArgFiles)arg).hrefs;
						for (int i = 0; i < hrefs.size(); i++) {
							hrefs.set(i, contextDir.toURI().resolve(hrefs.get(i)).toString());
						}
					}
				}
				
			} else {
			
				//	if (contextZipUpload == null) {
				if (contextDir.list().length == 0) {
					contextZipFile = null;
				} else {
					try {
						contextZipFile = File.createTempFile("jobContext", ".zip");
						Logger.debug("Created job context zip file: "+contextZipFile);
					} catch (IOException e) {
						Logger.error("Unable to create temporary job context ZIP file.", e);
						throw new RuntimeErrorException(new Error(e), "Unable to create temporary job context ZIP file.");
					}
					try {
						utils.Files.zip(contextDir, contextZipFile);
					} catch (IOException e) {
						Logger.error("Unable to zip context directory.", e);
						throw new RuntimeErrorException(new Error(e), "Unable to zip context directory.");
					}
				}
				//	} else {
				//		contextZipFile = contextZipUpload.getFile();
				//		try {
				//			Logger.debug("adding contents of '"+contextDir+"' to the ZIP '"+contextZipFile+"'");
				//			utils.Files.addDirectoryContentsToZip(contextZipFile, contextDir);
				//		} catch (IOException e) {
				//			Logger.error("Unable to add files to existing context ZIP file.", e);
				//			throw new RuntimeErrorException(new Error(e), "Unable to add files to existing context ZIP file.");
				//		}
				//	}
			}

		}
		
		Map<String,String> callbacks = new HashMap<String,String>();
		if (play.Play.isDev()) { // TODO: only in dev until the callback API is fully implemented
			callbacks.put("messages", routes.Callbacks.postCallback("messages").absoluteURL(request()));
			callbacks.put("status", routes.Callbacks.postCallback("status").absoluteURL(request()));
		}
		
		if (contextZipFile == null)
			Logger.debug("No files in context, submitting job without context ZIP file");
		else
			Logger.debug("Context ZIP file is present, submitting job with context ZIP file");
		
		Pipeline2WSResponse job;
		String jobId;
		try {
			job = org.daisy.pipeline.client.Jobs.post(
					Setting.get("dp2ws.endpoint"), Setting.get("dp2ws.authid"), Setting.get("dp2ws.secret"),
					scriptForm.script.href, scriptForm.script.arguments, contextZipFile, callbacks
			);
			
			if (job.status != 200 && job.status != 201) {
				return Application.error(job.status, job.statusName, job.statusDescription, job.asText());
			}
			
			jobId = XPath.selectText("/*/@id", job.asXml(), Pipeline2WS.ns);
			
		} catch (Pipeline2WSException e) {
			Logger.error(e.getMessage(), e);
			return Application.error(500, "Sorry, something unexpected occured", "A problem occured while communicating with the Pipeline 2 framework", e.getMessage());
		}
		
		Job webUiJob = new Job(jobId, user);
		webUiJob.nicename = id;
		if (scriptForm.uploads != null && scriptForm.uploads.size() > 0) {
			String filenames = "";
			int i = 0;
			for (Long uploadId : scriptForm.uploads.keySet()) {
				if (i > 0)
					filenames += ", ";
				if (i++ >= 3) {
					filenames += "...";
					break;
				}
				filenames += scriptForm.uploads.get(uploadId).getFile().getName();
			}
			if (filenames.length() > 0)
				webUiJob.nicename = id + " ("+filenames+")";
		}
		webUiJob.started = new Date();
		webUiJob.save(Application.datasource);
		NotificationConnection.push(webUiJob.user, new Notification("job-started-"+webUiJob.id, webUiJob.started.toString()));
		for (Long uploadId : scriptForm.uploads.keySet()) {
			// associate uploads with job
			scriptForm.uploads.get(uploadId).job = jobId;
			scriptForm.uploads.get(uploadId).save(Application.datasource);
		}
		
		webUiJob.pushNotifications();
		
		if (user.id < 0 && scriptForm.guestEmail != null) {
			String jobUrl = routes.Jobs.getJob(jobId).absoluteURL(request())+"?guestid="+-Long.parseLong(session("userid"));
			String html = views.html.Account.emailJobCreated.render(jobUrl, webUiJob.nicename).body();
			String text = "To view your Pipeline 2 job, go to this web address: " + jobUrl;
			if (!Account.sendEmail("Job started: "+webUiJob.nicename, html, text, scriptForm.guestEmail, scriptForm.guestEmail))
				flash("error", "Was unable to send the e-mail.");
		}
		
		return redirect(controllers.routes.Jobs.getJob(jobId));
	}
	
}
