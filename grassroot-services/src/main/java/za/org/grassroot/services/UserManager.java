package za.org.grassroot.services;

import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import za.org.grassroot.core.domain.Group;
import za.org.grassroot.core.domain.User;
import za.org.grassroot.core.domain.UserCreateRequest;
import za.org.grassroot.core.domain.VerificationTokenCode;
import za.org.grassroot.core.dto.UserDTO;
import za.org.grassroot.core.enums.UserLogType;
import za.org.grassroot.core.repository.GroupRepository;
import za.org.grassroot.core.repository.UserRepository;
import za.org.grassroot.core.repository.UserRequestRepository;
import za.org.grassroot.core.util.MaskingUtil;
import za.org.grassroot.core.util.PhoneNumberUtil;
import za.org.grassroot.messaging.producer.GenericJmsTemplateProducerService;
import za.org.grassroot.services.exception.NoSuchUserException;
import za.org.grassroot.services.exception.UserExistsException;
import za.org.grassroot.services.util.CacheUtilService;

import javax.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * @author Lesetse Kimwaga
 */

@Service
@Transactional
public class UserManager implements UserManagementService, UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(UserManager.class);

    private static final int PAGE_SIZE = 50;
    @Autowired
    GenericJmsTemplateProducerService jmsTemplateProducerService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private GroupRepository groupRepository;
    @Autowired
    private GroupBroker groupBroker;
    @Autowired
    private PasswordTokenService passwordTokenService;
    @Autowired
    private EventManagementService eventManagementService;
    @Autowired
    private CacheUtilService cacheUtilService;
    @Autowired
    private AsyncUserService asyncUserService;
    @Autowired
    private UserRequestRepository userCreateRequestRepository;


    @Override
    public User load(String userUid) {
        return userRepository.findOneByUid(userUid);
    }

    @Override
    public User createUserProfile(User userProfile) {
        return userRepository.save(userProfile);
    }

    @Override
    public User createUserWebProfile(User userProfile) throws UserExistsException {

        Assert.notNull(userProfile, "User is required");
        Assert.hasText(userProfile.getPhoneNumber(), "User phone number is required");

        User userToSave;
        String phoneNumber = PhoneNumberUtil.convertPhoneNumber(userProfile.getPhoneNumber());
        boolean userExists = userExist(phoneNumber);

        if (userExists) {

            System.out.println("The user exists, and their web profile is set to: " + userProfile.isHasWebProfile());

            User userToUpdate = loadOrSaveUser(phoneNumber);
            if (userToUpdate.isHasWebProfile()) {
                System.out.println("This user has a web profile already");
                throw new UserExistsException("User '" + userProfile.getUsername() + "' already has a web profile!");
            }

            if (!userToUpdate.hasName()) {
                userToUpdate.setDisplayName(userProfile.getFirstName() + " " + userProfile.getLastName());
            }

            userToUpdate.setFirstName(userProfile.getFirstName());
            userToUpdate.setLastName(userProfile.getLastName());

            userToUpdate.setUsername(phoneNumber);
            userToUpdate.setHasWebProfile(true);
            userToUpdate.setHasInitiatedSession(true);
            userToSave = userToUpdate;

        } else {

            userProfile.setPhoneNumber(phoneNumber);
            userProfile.setUsername(phoneNumber);
            // for some reason String.join was not inserting the space properly, so changing to a straight concatenation;
            userProfile.setDisplayName(userProfile.getFirstName() + " " + userProfile.getLastName());
            userProfile.setHasWebProfile(true);
            userToSave = userProfile;
        }

        if (passwordEncoder != null) {
            userToSave.setPassword(passwordEncoder.encode(userProfile.getPassword()));
        } else {
            log.warn("PasswordEncoder not set, skipping password encryption...");
        }

        try {
            User userToReturn = userRepository.saveAndFlush(userToSave);
            if (userExists)
                asyncUserService.recordUserLog(userToReturn.getId(), UserLogType.CREATED_IN_DB, "User first created via web sign up");
            asyncUserService.recordUserLog(userToReturn.getId(), UserLogType.CREATED_WEB, "User created web profile");
            return userToReturn;
        } catch (final Exception e) {
            e.printStackTrace();
            log.warn(e.getMessage());
            throw new UserExistsException("User '" + userProfile.getUsername() + "' already exists!");
        }

    }

    @Override
    public User createAndroidUserProfile(UserDTO userDTO) throws UserExistsException {
        Assert.notNull(userDTO);
        User userProfile = new User(userDTO.getPhoneNumber(), userDTO.getDisplayName());
        User userToSave;
        String phoneNumber = PhoneNumberUtil.convertPhoneNumber(userProfile.getPhoneNumber());
        boolean userExists = userExist(phoneNumber);

        if (userExists) {

            User userToUpdate = loadOrSaveUser(phoneNumber);
            if (userToUpdate.hasAndroidProfile()) {

                throw new UserExistsException("User '" + userProfile.getUsername() + "' already has a android profile!");
            }

            userToUpdate.setUsername(phoneNumber);
            userToUpdate.setHasAndroidProfile(true);
            userToUpdate.setHasInitiatedSession(true);
            userToSave = userToUpdate;

        } else {

            userProfile.setPhoneNumber(phoneNumber);
            userProfile.setUsername(phoneNumber);
            userProfile.setDisplayName(userDTO.getDisplayName());
            userProfile.setHasAndroidProfile(true);
            userToSave = userProfile;
        }

        try {
            User userToReturn = userRepository.saveAndFlush(userToSave);
            if (userExists)
                asyncUserService.recordUserLog(userToReturn.getId(), UserLogType.CREATED_IN_DB, "User first created via web sign up");
            asyncUserService.recordUserLog(userToReturn.getId(), UserLogType.REGISTERED_ANDROID, "User created android profile");
            return userToReturn;
        } catch (final Exception e) {
            e.printStackTrace();
            log.warn(e.getMessage());
            throw new UserExistsException("User '" + userProfile.getUsername() + "' already exists!");
        }

    }

    @Override
    public String generateAndroidUserVerifier(String phoneNumber, String displayName) {
        Objects.nonNull(phoneNumber);
        phoneNumber = PhoneNumberUtil.convertPhoneNumber(phoneNumber);
        if (displayName != null) {
            UserCreateRequest userCreateRequest = userCreateRequestRepository.findByPhoneNumber(phoneNumber);
            if (userCreateRequest == null) {
                userCreateRequest = new UserCreateRequest(phoneNumber, displayName, Instant.now());
            } else {
                userCreateRequest.setDisplayName(displayName);
                userCreateRequest.setCreationTime(Instant.now());
            }
            userCreateRequestRepository.save(userCreateRequest);
        }
        VerificationTokenCode token = passwordTokenService.generateAndroidVerificationCode(phoneNumber);
        return token.getCode();
    }

    @Override
    public long getUserCount() {
        // todo: switch this to a count query in repository, though, won't be called often, so not urgent
        return userRepository.count();
    }


    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

        if (StringUtils.isEmpty(username)) {
            throw new UsernameNotFoundException("Username not found.");
        }

        User user = userRepository.findByUsername(username.toLowerCase().trim());
        if (user == null) {
            throw new UsernameNotFoundException("Username not found.");
        }

        // let's initialize standard roles and membership hibernate collection this way
        user.getAuthorities();

        return user;
    }

    @Override
    public User save(User userToSave) {
        return userRepository.save(userToSave);
    }

    /**
     * Creating some functions to internalize conversion of phone numbers and querying
     */

    @Override
    public User loadOrSaveUser(String inputNumber) {
        String phoneNumber = PhoneNumberUtil.convertPhoneNumber(inputNumber);
        if (!userExist(phoneNumber)) {
            User sessionUser = new User(phoneNumber);
            sessionUser.setUsername(phoneNumber);
            User newUser = userRepository.save(sessionUser);
            asyncUserService.recordUserLog(newUser.getId(), UserLogType.CREATED_IN_DB, "Created via loadOrSaveUser");
            return newUser;
        } else {
            return userRepository.findByPhoneNumber(phoneNumber);
        }
    }

    /**
     * Methods to keep track also of where a user is in the ussd menu flow, so can return them to that spot if time out
     */
    public User loadOrSaveUser(String inputNumber, String currentUssdMenu) {
        User sessionUser = loadOrSaveUser(inputNumber);
        saveUssdMenu(sessionUser, currentUssdMenu);
        return sessionUser;
    }

    private void saveUssdMenu(User user, String menuToSave) {
        log.info("USSD menu passed to services: " + menuToSave);
        cacheUtilService.putUssdMenuForUser(user.getPhoneNumber(), menuToSave);
        user.setLastUssdMenu(menuToSave); // probably remove this once we have logging & are using cache
        user = userRepository.save(user);
        log.info("USSD menu stored: " + user.getLastUssdMenu());
    }

    /*
    Method to load or save a user and store that they have initiated the session. Putting it in services and making it
    distinct from standard loadOrSaveUser because we may want to optimize it aggressively in future.
     */

    @Override
    public User loadOrSaveUser(String inputNumber, boolean isInitiatingSession) {
        User sessionUser = loadOrSaveUser(inputNumber);
        sessionUser.setHasInitiatedSession(isInitiatingSession);
        return userRepository.save(sessionUser);
    }

    @Override
    public User findByInputNumber(String inputNumber) throws NoSuchUserException {
        User sessionUser = userRepository.findByPhoneNumber(PhoneNumberUtil.convertPhoneNumber(inputNumber));
        if (sessionUser == null) throw new NoSuchUserException("Could not find user with phone number: " + inputNumber);
        return sessionUser;
    }

    @Override
    public User findByInputNumber(String inputNumber, String currentUssdMenu) throws NoSuchUserException {

        User sessionUser = userRepository.findByPhoneNumber(PhoneNumberUtil.convertPhoneNumber(inputNumber));
        cacheUtilService.putUssdMenuForUser(inputNumber, currentUssdMenu);

        return sessionUser;
    }

    @Override
    public List<User> searchByGroupAndNameNumber(String groupUid, String nameOrNumber) {
        return userRepository.findByGroupsPartOfAndDisplayNameContainingIgnoreCaseOrPhoneNumberLike(
                groupBroker.load(groupUid), "%" + nameOrNumber + "%", "%" + nameOrNumber + "%");
    }

    @Override
    public List<User> getUsersFromNumbers(List<String> listOfNumbers) {

        List<User> usersToAdd = new ArrayList<User>();

        for (String inputNumber : listOfNumbers) {
            String phoneNumber = PhoneNumberUtil.convertPhoneNumber(inputNumber);
            if (!userExist(phoneNumber)) {
                User userToCreate = userRepository.save(new User(phoneNumber));
                usersToAdd.add(userToCreate);
                asyncUserService.recordUserLog(userToCreate.getId(), UserLogType.CREATED_IN_DB, "Created via multi member add");
            } else {
                usersToAdd.add(findByInputNumber(inputNumber));
            }
        }
        return usersToAdd;
    }

    @Override
    public List<User> getGroupMembersSortedById(Group group) {
        return userRepository.findByGroupsPartOfOrderByIdAsc(group);
    }

    @Override
    public Page<User> getGroupMembers(Group group, int pageNumber, int pageSize) {
        return userRepository.findByGroupsPartOf(group, new PageRequest(pageNumber,pageSize));
    }

    @Override
    public boolean userExist(String phoneNumber) {
        return userRepository.existsByPhoneNumber(phoneNumber);
    }

    @Override
    public boolean isFirstInitiatedSession(User user) {
        // may want to reload from DB, but could slow it down quite a bit
        return !user.isHasInitiatedSession();
    }

    @Override
    public boolean isPartOfActiveGroups(User user) {
        return (groupRepository.countByMembershipsUserAndActiveTrue(user) > 0);
    }

    @Override
    public boolean needsToRenameSelf(User sessionUser) {
        return sessionUser.needsToRenameSelf(10); // 5 min gap as placeholder for now, to make more a session count if possible
    }

    @Override
    public boolean needsToRSVP(User sessionUser) {
        // todo: as noted elsewhere, probably want to optimize this quite aggressively
        return eventManagementService.getOutstandingRSVPForUser(sessionUser).size() > 0;
    }

    @Override
    public boolean needsToVote(User sessionUser) {
        log.info("Checking if vote outstanding for user: " + sessionUser);
        return eventManagementService.getOutstandingVotesForUser(sessionUser).size() > 0;
    }

    @Override
    public boolean needsToVoteOrRSVP(User sessionUser) {
        // note: inserting a hasUpcomingEvents here makes it faster before cache is warmed up but slower after
        // as then will be doing a quick count query but still slower than cache retrieval, trade-off to monitor
        return (needsToVote(sessionUser) || needsToRSVP(sessionUser));
    }

    /*
    Method for user to reset password themselves, relies on them being able to access a token
     */

    @Override
    public User resetUserPassword(String username, String newPassword, String token) {

        log.info("Inside reset user password ...");

        User user = userRepository.findByUsername(PhoneNumberUtil.convertPhoneNumber(username));

        log.info("Found this user: " + user);

        if (passwordTokenService.isVerificationCodeValid(user, token)) {
            String encodedPassword = passwordEncoder.encode(newPassword);
            user.setPassword(encodedPassword);
            user = userRepository.save(user);
        }
        return user;
    }

    /*
    Method for an admin user to be able to reset a password for a user, if they don't have a means to get the token
    Notes: This really should be made a temporary password that requires the user to generate it when they log in
    Also, need to add the various permissions to make sure the admin user is admin, etc etc
     */

    @Override
    public User resetUserPassword(String username, String newPassword, User adminUser, String adminPassword) {

        User userToReset = userRepository.findByUsername(PhoneNumberUtil.convertPhoneNumber(username));

        try {

            // Authentication authentication = new UsernamePasswordAuthenticationToken(adminUser, null, adminUser.getAuthorities());
            String encodedPassword = passwordEncoder.encode(newPassword);
            userToReset.setPassword(encodedPassword);
            userToReset = userRepository.save(userToReset);

        } catch (Exception e) {
            throw new AuthenticationServiceException("Error, admin user could not be authenticated.");
        }

        return userToReset;
    }

    @Override
    public User fetchUserByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    @Override
    public User setInitiatedSession(User sessionUser) {
        sessionUser.setHasInitiatedSession(true);
        jmsTemplateProducerService.sendWithNoReply("welcome-messages", new UserDTO(sessionUser));
        asyncUserService.recordUserLog(sessionUser.getId(), UserLogType.INITIATED_USSD, "First USSD active session");
        return userRepository.save(sessionUser);
    }

    @Override
    public Group fetchGroupUserMustRename(User user) {
        Group lastCreatedGroup = groupRepository.findFirstByCreatedByUserOrderByIdDesc(user);
        if (lastCreatedGroup != null && lastCreatedGroup.isActive() && !lastCreatedGroup.hasName())
            return lastCreatedGroup;
        else
            return null;
    }

    @Override
    public String getLastUssdMenu(String inputNumber) {
        return cacheUtilService.fetchUssdMenuForUser(inputNumber);
    }

    @Override
    public User resetLastUssdMenu(User sessionUser) {
        cacheUtilService.clearUssdMenuForUser(sessionUser.getPhoneNumber());
        sessionUser.setLastUssdMenu(null); // as above, to make async &/or remove once cache and async all working
        return userRepository.save(sessionUser);
    }

    @Override
    public User setLastUssdMenu(User sessionUser, String lastUssdMenu) {
        cacheUtilService.putUssdMenuForUser(sessionUser.getPhoneNumber(), lastUssdMenu);
        return sessionUser;
    }

    @Override
    public User setDisplayName(User user, String displayName) {
        user.setDisplayName(displayName);
        return userRepository.save(user);
    }

    @Override
    public User setUserLanguage(User sessionUser, String locale) {
        sessionUser.setLanguageCode(locale);
        cacheUtilService.putUserLanguage(sessionUser.getPhoneNumber(), locale);
        return userRepository.save(sessionUser);
    }

    @Override
    public LinkedHashMap<String, String> getImplementedLanguages() {

        // todo: replace calls to one in USSDController to this one

        LinkedHashMap<String, String> languages = new LinkedHashMap<>();

        languages.put("en", "English");
        languages.put("nso", "Sepedi");
        languages.put("st", "Sesotho");
        languages.put("ts", "Tsonga");
        languages.put("zu", "Zulu");

        return languages;
    }

    /*
    SECTION: methods to return a masked user entity, for analytics
     */
    @Override
    public User loadUserMasked(Long userId) {
        return MaskingUtil.maskUser(userRepository.findOne(userId));
    }

    @Override
    public List<User> loadAllUsersMasked() {
        List<User> maskedUsers = new ArrayList<>();
        // todo: work out a much quicker way of doing this than the loop (could get _very_ long)
        for (User user : userRepository.findAll()) {
            maskedUsers.add(MaskingUtil.maskUser(user));
        }
        return maskedUsers;
    }

    @Override
    public List<User> loadSubsetUsersMasked(List<Long> ids) {
        List<User> maskedUsers = new ArrayList<>();
        List<User> unmaskedUsers = userRepository.findAll(ids);
        for (User user : unmaskedUsers)
            maskedUsers.add(MaskingUtil.maskUser(user));
        return maskedUsers;
    }

    @Override
    public UserDTO loadUser(String phoneNumber) {
        return new UserDTO(userRepository.findByNumber(phoneNumber));
    }


    @Override
    public UserDTO loadUserCreateRequest(String phoneNumber) {
        UserCreateRequest userCreateRequest = userCreateRequestRepository.findByPhoneNumber(PhoneNumberUtil.convertPhoneNumber(phoneNumber));
        return (new UserDTO(userCreateRequest));
    }

    public void setPasswordEncoder(final PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }
}
