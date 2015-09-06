package za.org.grassroot.services;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import za.org.grassroot.core.domain.Group;
import za.org.grassroot.core.domain.User;
import za.org.grassroot.core.repository.GroupRepository;

import javax.jws.soap.SOAPBinding;
import javax.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;

/**
 * @author luke on 2015/08/14.
 *
 */

@Service
@Transactional
public class GroupManager implements GroupManagementService {

    @Autowired
    GroupRepository groupRepository;

    @Autowired
    UserManagementService userManager;

    /**
     * Have not yet created methods analogous to those in UserManager, as not sure if necessary
     * For the moment, using this to expose some basic group services for the application interfaces
     */

    @Override
    public Group loadGroup(Long groupId) {
        return groupRepository.findOne(groupId);
    }

    @Override
    public List<Group> getGroupsFromUser(User sessionUser) {
        // todo: add pagination
        return sessionUser.getGroupsPartOf();
    }

    @Override
    public Group saveGroup(Group groupToSave) {
        return groupRepository.save(groupToSave);
    }

    @Override
    public void deleteGroup(Group groupToDelete) {
        groupRepository.delete(groupToDelete);
    }

    //todo aakil send event notification to new group member
    @Override
    public Group addGroupMember(Long currentGroupId, Long newMemberId) {
        return addGroupMember(getGroupById(currentGroupId),userManager.getUserById(newMemberId));
    }
    @Override
    public Group addGroupMember(Group currentGroup, User newMember) {
        currentGroup.addMember(newMember);
        return groupRepository.save(currentGroup);
    }

    @Override
    public Group createNewGroup(Long creatingUserId, List<String> phoneNumbers) {
        return createNewGroup(userManager.getUserById(creatingUserId),phoneNumbers);
    }

    @Override
    public Group createNewGroup(User creatingUser, List<String> phoneNumbers) {

        // todo: consider some way to check if group "exists", needs a solid "equals" logic
        // todo: defaulting to using Lists as Collection type for many-many, but that's an amateur decision ...

        Group groupToCreate = new Group();

        groupToCreate.setCreatedByUser(creatingUser);
        groupToCreate.setGroupName(""); // column not-null, so use blank string as default

        List<User> groupMembers = userManager.getUsersFromNumbers(phoneNumbers);
        groupMembers.add(creatingUser);
        groupToCreate.setGroupMembers(groupMembers);

        return groupRepository.save(groupToCreate);

    }

    //todo aakil send event notification to new group member
    @Override
    public Group addNumbersToGroup(Long groupId, List<String> phoneNumbers) {

        Group groupToExpand = loadGroup(groupId);
        List<User> groupNewMembers = userManager.getUsersFromNumbers(phoneNumbers);

        for (User newMember : groupNewMembers)
            groupToExpand.addMember(newMember);

        return groupRepository.save(groupToExpand);

    }

    /*
    Methods to implement finding last group and prompting to rename if unnamed. May make this a little more complex
    in future (e.g., check not just last created group, but any unnamed created groups above X users, or so on). Hence
    a little duplication / redundancy for now.
     */
    @Override
    public Group getLastCreatedGroup(User creatingUser) {
        return groupRepository.findFirstByCreatedByUserOrderByIdDesc(creatingUser);
    }

    @Override
    public boolean needsToRenameGroup(User sessionUser) {
        Group lastCreatedGroup = getLastCreatedGroup(sessionUser);
        return (lastCreatedGroup != null && !lastCreatedGroup.hasName());
    }

    @Override
    public Long groupToRename(User sessionUser) {
        return getLastCreatedGroup(sessionUser).getId();
    }

    @Override
    public List<Group> getCreatedGroups(User creatingUser) {
        return groupRepository.findByCreatedByUser(creatingUser);
    }

    @Override
    public Group getGroupById(Long groupId) {
        return groupRepository.findOne(groupId);
    }

    /*
    returns the new sub-group
     */
    public Group createSubGroup(Long createdByUserId, Long groupId, String subGroupName) {
        return createSubGroup(userManager.getUserById(createdByUserId), loadGroup(groupId),subGroupName);
    }

    public Group createSubGroup(User createdByUser, Group group, String subGroupName) {
        return groupRepository.save(new Group(subGroupName,createdByUser,group));
    }

    @Override
    public List<User> getAllUsersInGroupAndSubGroups(Long groupId) {
        return getAllUsersInGroupAndSubGroups(loadGroup(groupId));
    }

    @Override
    public List<User> getAllUsersInGroupAndSubGroups(Group group) {
        List<User> userList = new ArrayList<User>();
        recursiveUserAdd(group,userList);
        return userList;
    }

    private void recursiveUserAdd(Group parentGroup, List<User> userList ) {

        for (Group childGroup : groupRepository.findByParent(parentGroup)) {
            recursiveUserAdd(childGroup,userList);
        }

        // add all the users at this level
        userList.addAll(parentGroup.getGroupMembers());

    }
}
