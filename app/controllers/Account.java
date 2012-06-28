package controllers;

import org.apache.commons.mail.DefaultAuthenticator;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.HtmlEmail;

import models.Setting;
import models.User;
import play.Logger;
import play.Play;
import play.data.Form;
import play.mvc.*;

public class Account extends Controller {
	
	final static Form<User> editDetailsForm = form(User.class);
	final static Form<User> resetPasswordForm = form(User.class);
	final static Form<User> activateAccountForm = form(User.class);
	
	/**
	 * GET /account
	 * Show information about the user, and a form letting the user change their details.
	 * @return
	 */
	public static Result overview() {
		if (FirstUse.isFirstUse())
    		return redirect(routes.FirstUse.getFirstUse());
		
		User user = User.authenticate(session("userid"), session("email"), session("password"));
		if (user == null || user.id < 0)
			return redirect(routes.Login.login());
    	
		return ok(views.html.Account.overview.render(form(User.class)));
	}
	
	/**
	 * POST /account
	 * Called when the GET /account form is submitted.
	 * @return
	 */
	public static Result changeDetails() {
		if (FirstUse.isFirstUse())
    		return redirect(routes.FirstUse.getFirstUse());
		
		User user = User.authenticate(session("userid"), session("email"), session("password"));
		if (user == null || user.id < 0)
			return redirect(routes.Login.login());
		
		Form<User> filledForm = editDetailsForm.bindFromRequest();
		
		boolean changedName = false;
		boolean changedEmail = false;
		boolean changedPassword = false;
		
		if (!user.name.equals(filledForm.field("name").valueOr(""))) {
			// Changed name
			changedName = true;
		}
		
		if (!user.email.equals(filledForm.field("email").valueOr(""))) {
			// Changed email
			changedEmail = true;
			
			if (User.findByEmail(filledForm.field("email").valueOr("")) != null)
	            filledForm.reject("email", "That e-mail address is already taken");
		}
    	
		if (!filledForm.field("newPassword").valueOr("").equals("")) {
			// Changed password
			changedPassword = true;
			
			if (!filledForm.field("newPassword").valueOr("").equals(filledForm.field("repeatPassword").valueOr(""))) {
				filledForm.reject("repeatPassword", "Passwords don't match.");
			
			} else if (filledForm.field("password").valueOr("").equals("")) {
				filledForm.reject("password", "You must enter your existing password, just so that we're extra sure that you are you.");
				
			} else {
				User oldUser = User.authenticateUnencrypted(user.email, filledForm.field("password").valueOr(""));
				if (oldUser == null)
					filledForm.reject("password", "The password you entered is wrong, please correct it and try again.");
			}
			
		} else if (filledForm.errors().containsKey("password")) {
			filledForm.errors().get("password").clear(); // No need to check the old password if the user isn't trying to set a new password
			Logger.debug("deleted all password error messages");
		}
		
		if (!changedName && !changedEmail && !changedPassword) {
			flash("success", "You did not submit any changes. No changes were made.");
			return redirect(routes.Account.overview());
			
		} else if (filledForm.hasErrors()) {
        	return badRequest(views.html.Account.overview.render(filledForm));
        	
        } else {
        	if (changedName)
        		user.name = filledForm.field("name").valueOr("");
        	if (changedEmail)
        		user.email = filledForm.field("email").valueOr("");
        	if (changedPassword)
        		user.setPassword(filledForm.field("newPassword").valueOr(""));
        	user.save();
        	session("name", user.name);
        	session("email", user.email);
        	session("password", user.password);
        	flash("success", "Your changes were saved successfully!");
        	return redirect(routes.Account.overview());
        }
	}
	
	/**
	 * GET /account/resetpassword
	 * 
	 * Show a form letting the user set a password without having one already.
	 * 
	 * @param email
	 * @param resetUid
	 * @return
	 */
	public static Result showResetPasswordForm(String email, String resetUid) {
		if (FirstUse.isFirstUse())
    		return redirect(routes.FirstUse.getFirstUse());
		
		User user = User.findByEmail(email);
		if (user == null || user.id < 0)
			return redirect(routes.Login.login());
		
		if (resetUid == null || !resetUid.equals(user.getActivationUid()))
			return redirect(routes.Login.login());
		
		return ok(views.html.Account.resetPassword.render(form(User.class), email, resetUid, user.active));
	}
	
	/**
	 * POST /account/resetpassword
	 * 
	 * Called when a user tries to set a password through the GET /account/resetpassword form.
	 * 
	 * @param email
	 * @param resetUid
	 * @return
	 */
	public static Result resetPassword(String email, String resetUid) {
		if (FirstUse.isFirstUse())
    		return redirect(routes.FirstUse.getFirstUse());
		
		User user = User.findByEmail(email);
		if (user == null || user.id < 0)
			return redirect(routes.Login.login());
		
		if (resetUid == null || !resetUid.equals(user.getActivationUid()))
			return redirect(routes.Login.login());
		
		Form<User> filledForm = resetPasswordForm.bindFromRequest();
        
		if (!filledForm.field("password").valueOr("").equals("") && !filledForm.field("password").valueOr("").equals(filledForm.field("repeatPassword").value()))
    		filledForm.reject("repeatPassword", "Password doesn't match.");
        
        if (filledForm.hasErrors()) {
        	return badRequest(views.html.Account.resetPassword.render(filledForm, email, resetUid, user.active));
        	
        } else {
        	user.setPassword(filledForm.field("password").valueOr(""));
        	user.active = true;
        	user.passwordLinkSent = null;
        	user.save();
        	session("name", user.name);
        	session("email", user.email);
        	session("password", user.password);
        	session("admin", user.admin+"");
        	return redirect(routes.Login.login());
        }
	}
	
	/**
	 * GET /account/activate
	 * Alias for showResetPasswordForm(email, resetUid), so that the URL looks nicer.
	 * @param email
	 * @param resetUid
	 * @return
	 */
	public static Result showActivateForm(String email, String activateUid) {
		return showResetPasswordForm(email, activateUid);
	}
	
	/**
	 * POST /account/activate
	 * Alias for resetPassword(email, resetUid), so that the URL looks nicer.
	 * @param email
	 * @param resetUid
	 * @return
	 */
	public static Result activate(String email, String activateUid) {
		return resetPassword(email, activateUid);
	}
	
	public static boolean sendEmail(String subject, String html, String text, String recipientName, String recipientEmail) {
		try {
			HtmlEmail email = new HtmlEmail();
			email.setAuthenticator(new DefaultAuthenticator(Setting.get("mail.username"), Setting.get("mail.password")));
			email.setDebug(Play.application().isDev());
			email.setHostName(Setting.get("mail.smtp.host"));
			email.getMailSession().getProperties().put("mail.debug", Play.application().isDev() ? "true" : "false");
			email.getMailSession().getProperties().put("mail.smtp.debug", Play.application().isDev() ? "true" : "false");
			email.getMailSession().getProperties().put("mail.smtps.auth", "true");
			email.getMailSession().getProperties().put("mail.smtps.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
			email.getMailSession().getProperties().put("mail.smtps.socketFactory.fallback", "false");
			email.getMailSession().getProperties().put("mail.smtp.starttls.enable", Setting.get("mail.smtp.ssl"));
			email.getMailSession().getProperties().put("mail.smtp.user", Setting.get("mail.from.email"));
			email.getMailSession().getProperties().put("mail.smtp.host", Setting.get("mail.smtp.host"));
			email.getMailSession().getProperties().put("mail.smtp.port", Setting.get("mail.smtp.port"));
			email.getMailSession().getProperties().put("mail.smtp.auth", "true");
			email.getMailSession().getProperties().put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
			email.getMailSession().getProperties().put("mail.smtp.socketFactory.fallback", "false");
			email.getMailSession().getProperties().put("mail.smtp.socketFactory.port", Setting.get("mail.smtp.port"));
			email.setFrom(Setting.get("mail.from.email"), Setting.get("mail.from.name"));
			email.setSubject("[DAISY Pipeline 2] "+subject); // TODO: customizable subject prefix
			email.setHtmlMsg(html);
			email.setTextMsg(text);
			email.addTo(recipientEmail, recipientName);
			email.setSSL(true);
			email.setTLS(true);
			email.send();
			return true;
		} catch (EmailException e) {
			Logger.error("EmailException occured while trying to send an e-mail!", e);
			e.printStackTrace();
			return false;
		}
	}
	
}
