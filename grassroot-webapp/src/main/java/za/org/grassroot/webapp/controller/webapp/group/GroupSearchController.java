package za.org.grassroot.webapp.controller.webapp.group;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.org.grassroot.core.domain.Group;
import za.org.grassroot.core.dto.TaskDTO;
import za.org.grassroot.integration.LearningService;
import za.org.grassroot.services.async.AsyncUserLogger;
import za.org.grassroot.services.exception.RequestorAlreadyPartOfGroupException;
import za.org.grassroot.services.group.GroupBroker;
import za.org.grassroot.services.group.GroupJoinRequestService;
import za.org.grassroot.services.group.GroupLocationFilter;
import za.org.grassroot.services.group.GroupQueryBroker;
import za.org.grassroot.services.task.TaskBroker;
import za.org.grassroot.webapp.controller.BaseController;
import za.org.grassroot.webapp.model.web.PublicGroupWrapper;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Created by luke on 2016/09/06.
 */
@Controller
@RequestMapping("/group/")
public class GroupSearchController extends BaseController {

	private static final Logger log = LoggerFactory.getLogger(GroupSearchController.class);
	private static final Pattern tokenPattern = Pattern.compile("[^a-zA-Z\\s]+");

	@Value("${grassroot.ussd.dialcode:*134*1994*}")
	private String ussdDialCode;

	@Value("${grassroot.learning.relatedterms.threshold:0.1}")
	private Double defaultDistanceThreshold;

	private final GroupBroker groupBroker;
	private final GroupQueryBroker groupQueryBroker;
	private final TaskBroker taskBroker;
	private final GroupJoinRequestService groupJoinRequestService;
	private final AsyncUserLogger userLogger;
	private final LearningService learningService;

	@Autowired
	public GroupSearchController(GroupBroker groupBroker, GroupQueryBroker groupQueryBroker, TaskBroker taskBroker,
								 GroupJoinRequestService groupJoinRequestService, AsyncUserLogger userLogger, LearningService learningService) {
		this.groupBroker = groupBroker;
		this.groupQueryBroker = groupQueryBroker;
		this.taskBroker = taskBroker;
		this.groupJoinRequestService = groupJoinRequestService;
		this.userLogger = userLogger;
		this.learningService = learningService;
	}


	@RequestMapping(value = "join/request", method = RequestMethod.POST)
	public String requestToJoinGroup(Model model, @RequestParam(value="uid") String groupToJoinUid,
	                                 @RequestParam(value="description", required = false) String description,
	                                 HttpServletRequest request, RedirectAttributes attributes) {
		// in case modal goes weird on old / new / etc browsers (because CSS/JS)
		if ("error".equals(groupToJoinUid)) {
			addMessage(attributes, MessageType.ERROR, "group.join.request.error", request);
			return "redirect:/home";
		} else {
			try {
				groupJoinRequestService.open(getUserProfile().getUid(), groupToJoinUid, description);
				addMessage(attributes, MessageType.INFO, "group.join.request.done", request);
				return "redirect:/home";
			} catch (RequestorAlreadyPartOfGroupException e) {
				addMessage(attributes, MessageType.INFO, "group.join.request.member", request);
				attributes.addAttribute("groupUid", groupToJoinUid);
				return "redirect:/group/view";
			}
		}
	}

	@RequestMapping(value = "/search")
	public String searchForGroup(@RequestParam String term, @RequestParam(required = false) String groupUid,
								 Model model, RedirectAttributes attributes, HttpServletRequest request) {

		boolean resultFound = false;

		if (!StringUtils.isEmpty(groupUid) && groupQueryBroker.groupExists(groupUid)) {
			attributes.addAttribute("groupUid", groupUid);
			return "redirect:/group/view";
		} else if (term.isEmpty()) {
			addMessage(model, BaseController.MessageType.ERROR, "search.error.empty", request);
		} else {
			boolean onlyDigits = tokenPattern.matcher(term.trim()).find();
			if (onlyDigits && !userLogger.hasUsedJoinCodeRecently(getUserProfile().getUid())) {
				String tokenSearch = term.contains(ussdDialCode) ? term.substring(ussdDialCode.length(), term.length() - 1) : term;
				log.debug("searching for group ... token to use ... " + tokenSearch);
				Optional<Group> groupByToken = groupQueryBroker.findGroupFromJoinCode(tokenSearch);
				if (groupByToken.isPresent()) {
					model.addAttribute("group", groupByToken.get());
					resultFound = true;
				}
			} else {
				// just for testing since no UI support yet exists...
				// GroupLocationFilter locationFilter = new GroupLocationFilter(new GeoLocation(45.567641, 18.701211), 30000, true);
				GroupLocationFilter locationFilter = null;
				final String description = getMessage("search.group.desc");
				final String userUid = getUserProfile().getUid();

				List<PublicGroupWrapper> publicGroups = groupQueryBroker.findPublicGroups(userUid, term, locationFilter, false).stream()
						.map(g -> new PublicGroupWrapper(g, description)).collect(Collectors.toList());

				List<String> relatedTerms = learningService.findRelatedTerms(term).entrySet().stream()
						.sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
						.map(Map.Entry::getKey)
						.collect(Collectors.toList());

				log.info("Received this list of related terms from learning service: {}", relatedTerms);

				// testing line (remove once related terms operational)
				// relatedTerms = Arrays.asList("Water", "Taps", "Pipes");


				model.addAttribute("groupCandidates", publicGroups);
				model.addAttribute("relatedTerms", relatedTerms);


				List<Group> memberGroups = groupQueryBroker.searchUsersGroups(userUid, term, false);
				List<TaskDTO> memberTasks = taskBroker.searchForTasks(userUid, term);
				model.addAttribute("foundGroups", memberGroups);
				model.addAttribute("foundTasks", memberTasks);

				resultFound = !publicGroups.isEmpty() || !memberGroups.isEmpty() || !memberTasks.isEmpty();
			}
		}

		model.addAttribute("resultFound", resultFound);
		return "group/results";
	}

	@RequestMapping(value = "join/approve")
	public String approveJoinRequest(@RequestParam String requestUid, @RequestParam(required = false) String source,
									 RedirectAttributes attributes, HttpServletRequest request) {
		// note: join request service will do the permission checking etc and throw an error before proceeding
		long startTime = System.currentTimeMillis();
		groupJoinRequestService.approve(getUserProfile().getUid(), requestUid);
		log.info("Join request approval took: {} msecs", System.currentTimeMillis() - startTime);
		final String groupUid = groupJoinRequestService.loadRequest(requestUid).getGroup().getUid();

		if (!StringUtils.isEmpty(source) && "home".equals(source)) {
			addMessage(attributes, MessageType.SUCCESS, "home.join.request.approved", new String[] { groupUid }, request);
			return "redirect:/home";
		} else {
			addMessage(attributes, MessageType.INFO, "group.join.request.approved", request);
			attributes.addAttribute("groupUid", groupUid);
			return "redirect:/group/view";
		}
	}

	@RequestMapping(value = "join/decline")
	public String declineJoinRequest(@RequestParam String requestUid, @RequestParam(required = false) String source,
									 HttpServletRequest request, RedirectAttributes attributes) {
		groupJoinRequestService.decline(getUserProfile().getUid(), requestUid);
		final String groupUid = groupJoinRequestService.loadRequest(requestUid).getGroup().getUid();

		if (!StringUtils.isEmpty(source) && "home".equals(source)) {
			addMessage(attributes, MessageType.INFO, "home.join.request.declined", new String[] { groupUid }, request);
			return "redirect:/home";
		} else {
			addMessage(attributes, MessageType.INFO, "group.join.request.declined", request);
			attributes.addAttribute("groupUid", groupUid);
			return "redirect:/group/view";
		}
	}

	@RequestMapping(value = "join/token", method = RequestMethod.POST)
	public String joinGroup(RedirectAttributes attributes, @RequestParam String groupUid, @RequestParam String token, HttpServletRequest request) {
		groupBroker.addMemberViaJoinCode(getUserProfile().getUid(), groupUid, token);
		addMessage(attributes, MessageType.SUCCESS, "group.join.success", request);
		attributes.addAttribute("groupUid", groupUid);
		return "redirect:/group/view";
	}


}
