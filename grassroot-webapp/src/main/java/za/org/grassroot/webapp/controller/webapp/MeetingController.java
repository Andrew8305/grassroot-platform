package za.org.grassroot.webapp.controller.webapp;

import edu.emory.mathcs.backport.java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.org.grassroot.core.domain.*;
import za.org.grassroot.core.dto.ResponseTotalsDTO;
import za.org.grassroot.core.enums.EventRSVPResponse;
import za.org.grassroot.services.*;
import za.org.grassroot.webapp.controller.BaseController;

import javax.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;


/**
 * Created by luke on 2015/09/11.
 */
@Controller
@RequestMapping("/meeting/")
@SessionAttributes("meeting")
public class MeetingController extends BaseController {

    Logger log = LoggerFactory.getLogger(MeetingController.class);

    @Autowired
    private GroupBroker groupBroker;

    @Autowired
    private EventBroker eventBroker;

    @Autowired
    private PermissionBroker permissionBroker;

    @Autowired
    private EventManagementService eventManagementService;

    @Autowired
    private EventLogManagementService eventLogManagementService;

    /**
     * Meeting creation
     */

    @RequestMapping("create")
    public String createMeetingIndex(Model model, @RequestParam(value="groupUid", required=false) String groupUid) {

        boolean groupSpecified;
        User sessionUser = getUserProfile();
        Meeting meeting = Meeting.makeEmpty(sessionUser);
        meeting.setRsvpRequired(true); // since this is default (and Thymeleaf doesn't handle setting it in template well)
        meeting.setReminderType(EventReminderType.GROUP_CONFIGURED);
        meeting.setCustomReminderMinutes(24 * 60);

        if (groupUid != null) {
            Group group = groupBroker.load(groupUid);
            model.addAttribute("group", group);
            meeting.setParent(group);
            groupSpecified = true;
        } else {
            // todo: filter by permissions, and include number of members (for confirm modal)
            model.addAttribute("userGroups", permissionBroker.getActiveGroups(sessionUser, Permission.GROUP_PERMISSION_CREATE_GROUP_MEETING));
            groupSpecified = false;
        }

        model.addAttribute("meeting", meeting);
        model.addAttribute("groupSpecified", groupSpecified); // slightly redundant, but use it to tell Thymeleaf what to do
        model.addAttribute("reminderOptions", reminderMinuteOptions(false));

        log.info("Meeting that we are passing: " + meeting.toString());
        return "meeting/create";

    }

    @RequestMapping(value = "create", method = RequestMethod.POST)
    public String createMeeting(Model model, @ModelAttribute("meeting") Meeting meeting, BindingResult bindingResult,
                                @RequestParam(value="selectedGroupUid", required=false) String selectedGroupUid,
                                HttpServletRequest request, RedirectAttributes redirectAttributes) {

        // todo: add error handling and validation
        // todo: check that we have all the needed information and/or add a confirmation screen
        // todo: put this data transformation else where:Maybe Wrapper?

        log.info("The event passed back to us: " + meeting.toString());
        log.info("Event location set as: " + meeting.getEventLocation());

        String groupUid = (selectedGroupUid == null) ? meeting.resolveGroup().getUid() : selectedGroupUid;

        eventBroker.createMeeting(getUserProfile().getUid(), groupUid, JpaEntityType.GROUP, meeting.getName(),
                meeting.getEventStartDateTime(), meeting.getEventLocation(), meeting.isIncludeSubGroups(),
                meeting.isRsvpRequired(), meeting.isRelayable(), meeting.getReminderType(), meeting.getCustomReminderMinutes(),
                "", Collections.emptySet());

        log.info("Stored meeting, at end of creation method: " + meeting.toString());

        addMessage(redirectAttributes, MessageType.SUCCESS, "meeting.creation.success", request);
        redirectAttributes.addAttribute("groupUid", groupUid);
        return "redirect:/group/view";
    }

    /**
     * Meeting view and modification
     */

    @RequestMapping("view")
    public String viewMeetingDetails(Model model, @RequestParam String eventUid) {

        Meeting meeting = eventBroker.loadMeeting(eventUid);
        User user = getUserProfile();

        ResponseTotalsDTO meetingResponses = eventLogManagementService.getResponseCountForEvent(meeting);
        boolean canViewRsvps = permissionBroker.isGroupPermissionAvailable(
                user, meeting.resolveGroup(), Permission.GROUP_PERMISSION_VIEW_MEETING_RSVPS);
        boolean canAlterDetails = meeting.getCreatedByUser().equals(user);

        model.addAttribute("meeting", meeting);
        model.addAttribute("responseTotals", meetingResponses);
        model.addAttribute("canViewRsvps", canViewRsvps);

        model.addAttribute("canAlterDetails", canAlterDetails); // todo: maybe, or is organizer/committee?

        if (canViewRsvps) {
            // this is clunky, but it's for Thymeleaf
            Set<Map.Entry<User, EventRSVPResponse>> rsvpResponses = eventManagementService.getRSVPResponses(meeting).entrySet();
            model.addAttribute("rsvpResponses", rsvpResponses);
        }

        if (canAlterDetails) {
            model.addAttribute("reminderOptions", EventReminderType.values());
            model.addAttribute("customReminderOptions", reminderMinuteOptions(false));
            model.addAttribute("todos", meeting.getLogBooks());
        }

        if (meeting.getScheduledReminderTime() != null)
            model.addAttribute("scheduledReminderTime", Timestamp.from(meeting.getScheduledReminderTime())); // for thymeleaf

        return "meeting/view";
    }


    @RequestMapping(value = "modify", method=RequestMethod.POST)
    public String changeMeeting(Model model, @ModelAttribute("meeting") Meeting changedMeeting, HttpServletRequest request) {

        // todo: double check permissions in location update
        log.info("Okay, here is the meeting passed back ... " + changedMeeting);
        eventBroker.updateMeeting(getUserProfile().getUid(), changedMeeting.getUid(), changedMeeting.getName(),
                                  changedMeeting.getEventStartDateTime(), changedMeeting.getEventLocation());
        addMessage(model, MessageType.SUCCESS, "meeting.update.success", request);
        return viewMeetingDetails(model, changedMeeting.getUid());

    }

    @RequestMapping(value = "cancel", method=RequestMethod.POST)
    public String cancelMeeting(Model model, @ModelAttribute("meeting") Meeting meeting, BindingResult bindingResult,
                                RedirectAttributes redirectAttributes, HttpServletRequest request) {

        log.info("Meeting that is about to be cancelled: " + meeting.toString());
        eventBroker.cancel(getUserProfile().getUid(), meeting.getUid());
        addMessage(redirectAttributes, MessageType.SUCCESS, "meeting.cancel.success", request);
        return "redirect:/home";

    }

    @RequestMapping(value = "reminder", method=RequestMethod.POST)
    public String changeReminderSettings(Model model, @RequestParam String eventUid, @RequestParam EventReminderType reminderType,
                                         @RequestParam(value = "custom_minutes", required = false) Integer customMinutes, HttpServletRequest request) {

        // todo: maybe move this into a common place ... since call it from all controllers ...
        int minutes = (customMinutes != null && reminderType.equals(EventReminderType.CUSTOM)) ? customMinutes : 0;
        eventBroker.updateReminderSettings(getUserProfile().getUid(), eventUid, reminderType, minutes);
        Instant newScheduledTime = eventBroker.loadMeeting(eventUid).getScheduledReminderTime();
        if (newScheduledTime != null) {
            // todo: make sure this actually works properly with zones, on AWS in Ireland, etc ...
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("h:mm a' on 'E, d MMMM").withZone(ZoneId.systemDefault());
            String reminderTime = formatter.format(newScheduledTime);
            addMessage(model, MessageType.SUCCESS, "meeting.reminder.changed", new String[] {reminderTime}, request);
        } else {
            addMessage(model, MessageType.SUCCESS, "meeting.reminder.changed.none", request);
        }
        return viewMeetingDetails(model, eventUid);
    }

    /*@RequestMapping(value = "reminder", method=RequestMethod.POST)
    public String confirmReminder(Model model, @ModelAttribute("meeting") Meeting meeting, RedirectAttributes redirectAttributes,
                               HttpServletRequest request) {

        meeting = (Meeting) eventManagementService.loadEvent(meeting.getId());

        // todo: make sure this is a paid group before allowing it (not just that user is admin)
        if (request.isUserInRole("ROLE_SYSTEM_ADMIN") || request.isUserInRole("ROLE_ACCOUNT_ADMIN")) {

            model.addAttribute("entityId", meeting.getId());
            model.addAttribute("action", "remind");
            model.addAttribute("includeSubGroups", meeting.isIncludeSubGroups());

            String groupLanguage = meeting.getAppliesToGroup().getDefaultLanguage();
            log.info("Composing dummy message for confirmation ... Group language is ... " + groupLanguage);
            String message = (groupLanguage == null || groupLanguage.trim().equals("")) ?
                    eventManagementService.getDefaultLocaleReminderMessage(getUserProfile(), meeting) :
                    eventManagementService.getReminderMessageForConfirmation(groupLanguage, getUserProfile(), meeting);

            model.addAttribute("message", message);
            model.addAttribute("recipients", eventManagementService.getNumberInvitees(meeting));
            model.addAttribute("cost", eventManagementService.getCostOfMessagesDefault(meeting));
            return "meeting/remind_confirm";

        } else {
            // todo: go back to the meeting instead
            addMessage(redirectAttributes, MessageType.ERROR, "permission.denied.error", request);
            return "redirect:/home";

        }
    }*/

    @PreAuthorize("hasAnyRole('ROLE_SYSTEM_ADMIN', 'ROLE_ACCOUNT_ADMIN')")
    @RequestMapping(value = "/meeting/remind", method=RequestMethod.POST)
    public String sendReminder(Model model, @RequestParam("entityUid") String meetingUid, RedirectAttributes redirectAttributes,
                               HttpServletRequest request) {

        // todo: check for paid group & for feature enabled
        Meeting meeting = eventBroker.loadMeeting(meetingUid);
        eventBroker.sendManualReminder( getUserProfile().getUid(), meeting.getUid(), "");
        addMessage(redirectAttributes, MessageType.SUCCESS, "meeting.reminder.success", request);
        return "redirect:/home";

    }

    /**
     * Free text entry, for authorized accounts
     */

    // Major todo: make this secured against the user's role as 'admin' on an institutional account
    @PreAuthorize("hasAnyRole('ROLE_SYSTEM_ADMIN', 'ROLE_ACCOUNT_ADMIN')")
    @RequestMapping(value = "/meeting/free")
    public String sendFreeForm(Model model, @RequestParam(value="groupUid", required=false) String groupUid) {

        boolean groupSpecified;
        User sessionUser = getUserProfile();

        if (groupUid != null) {
            model.addAttribute("group", groupBroker.load(groupUid));
            groupSpecified = true;
        } else {
            Set<Group> activeGroups = permissionBroker.getActiveGroups(sessionUser, null); // only where organizer?
            model.addAttribute("userGroups", activeGroups);
            log.info("ZOG: MTG: userGroups ..." + activeGroups);
            groupSpecified = false;
        }
        model.addAttribute("groupSpecified", groupSpecified); // slightly redundant, but use it to tell Thymeleaf what to do
        return "meeting/free";
    }

    @PreAuthorize("hasAnyRole('ROLE_SYSTEM_ADMIN', 'ROLE_ACCOUNT_ADMIN')")
    @RequestMapping(value = "/meeting/free", method = RequestMethod.POST)
    public String confirmFreeMsg(Model model, @RequestParam String groupUid, @RequestParam(value="message") String message,
                                 @RequestParam(value="includeSubGroups", required=false) boolean includeSubgroups) {

        model.addAttribute("action", "free");
        model.addAttribute("entityId", groupUid);
        model.addAttribute("includeSubGroups", includeSubgroups);

        model.addAttribute("message", message);
        int recipients = userManagementService.fetchByGroup(groupUid, includeSubgroups).size();
        model.addAttribute("recipients", recipients);
        model.addAttribute("cost", recipients * 0.2);
        return "meeting/remind_confirm";

    }

    @PreAuthorize("hasAnyRole('ROLE_SYSTEM_ADMIN', 'ROLE_ACCOUNT_ADMIN')")
    @RequestMapping(value = "/meeting/free", method = RequestMethod.POST, params = {"confirmed"})
    public String sendFreeMsg(Model model, @RequestParam(value="entityUid") String groupUid, @RequestParam(value="message") String message,
                              @RequestParam(value="includeSubGroups", required=false) boolean includeSubgroups,
                              RedirectAttributes redirectAttributes, HttpServletRequest request) {

        // todo: check that this group is paid for (and filter on previous page)

        log.info("Sending free form message ... includeSubGroups set to ... " + includeSubgroups);

        // todo: this should be properly redesigned into directly calling "messaging service", bypassing whole fake event entity thing
        if (true) {
            throw new UnsupportedOperationException("This is not supported after event refactoring!!!");
        }
/*
        Event dummyEvent = eventManagementService.createEvent("", getUserProfile(), group, includeSubgroups);
        boolean messageSent = eventManagementService.sendManualReminder(dummyEvent, message);
        log.info("We just sent a free form message with result: " + messageSent);
*/

        redirectAttributes.addAttribute("groupUid", groupUid);
        addMessage(redirectAttributes, MessageType.SUCCESS, "sms.message.sent", request);
        return "redirect:/group/view";

    }

    /**
     * RSVP handling
     */

    @RequestMapping(value = "rsvp")
    public String rsvpYes(Model model, @RequestParam String eventUid, @RequestParam String answer,
                          HttpServletRequest request, RedirectAttributes attributes) {

        Meeting meeting = eventBroker.loadMeeting(eventUid);
        User user = getUserProfile();

        switch (answer) {
            case "yes":
                eventLogManagementService.rsvpForEvent(meeting, user, EventRSVPResponse.YES);
                addMessage(model, MessageType.SUCCESS, "meeting.rsvp.yes", request);
                return viewMeetingDetails(model, meeting.getUid());
            case "no":
                eventLogManagementService.rsvpForEvent(meeting, user, EventRSVPResponse.NO);
                addMessage(attributes, MessageType.ERROR, "meeting.rsvp.no", request);
                return "redirect:/home";
            default:
                addMessage(attributes, MessageType.ERROR, "meeting.rsvp.no", request);
                return "redirect:/home";
        }
    }

}
