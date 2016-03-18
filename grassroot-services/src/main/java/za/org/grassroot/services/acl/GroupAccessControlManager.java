package za.org.grassroot.services.acl;

import com.google.common.collect.ImmutableList;
import edu.emory.mathcs.backport.java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.acls.domain.ObjectIdentityImpl;
import org.springframework.security.acls.domain.PermissionFactory;
import org.springframework.security.acls.domain.PrincipalSid;
import org.springframework.security.acls.model.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import za.org.grassroot.core.domain.Group;
import za.org.grassroot.core.domain.Permission;
import za.org.grassroot.core.domain.User;
import za.org.grassroot.services.GroupManagementService;

import javax.transaction.Transactional;
import java.util.List;
import java.util.Set;

/**
 * @author Lesetse Kimwaga
 */
@Service
@Transactional
public class GroupAccessControlManager implements GroupAccessControlManagementService {

    private static Logger log = LoggerFactory.getLogger(GroupAccessControlManager.class);

    @Autowired
    private GroupManagementService groupManagementService;

    @Autowired
    private PermissionEvaluator permissionEvaluator;

    @Autowired
    @Qualifier("aclPermissionFactory")
    private PermissionFactory permissionFactory;

    @Autowired
    private MutableAclService mutableAclService;


    @Override
    public void addUserGroupPermissions(Group group, User user, Set<Permission> groupPermissions) {
        addGroupPermissions(group, Collections.singletonList(user), groupPermissions);
    }

    @Override
    public void addUserGroupPermissions(Group group, User addingToUser, User modifyingUser, Set<Permission> groupPermissions) {
        // USSD sessions (& those over API later) will not have user object in auth, so need to construct it
        // log.info("Adding permissions, authentication currently ... " + SecurityContextHolder.getContext().getAuthentication());
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            Authentication auth = new UsernamePasswordAuthenticationToken(modifyingUser, modifyingUser.getPassword(), modifyingUser.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        log.info("After null check & setting, context set to ... " + SecurityContextHolder.getContext().getAuthentication());
        addGroupPermissions(group, Collections.singletonList(addingToUser), groupPermissions);
    }

    @Override
    public void addUsersGroupPermissions(Group group, List<User> addingToUsers, User modifyingUser, Set<Permission> groupPermissions) {
        addGroupPermissions(group, addingToUsers, groupPermissions);
    }

    private void addGroupPermissions(Group group, List<User> addingToUsers, Set<Permission> groupPermissions) {
        try {
            log.info("Inside ACL setting .... Current authentication is ... " + SecurityContextHolder.getContext().getAuthentication());
            ObjectIdentity objectIdentity = new ObjectIdentityImpl(Group.class, group.getId());
            MutableAcl acl = getMutableAcl(objectIdentity);

            for (User user : addingToUsers) {
                log.info("Principal SID with user name ... " + user.getUsername());
                Sid sid = new PrincipalSid(user.getUsername());

                /**************************************************************
                 * Clear ALL permissions
                 **************************************************************/
                removePermissions(acl, sid);

                /**************************************************************
                 * Grant Permissions via an access control entry (ACE)
                 **************************************************************/

                for (Permission permission : groupPermissions) {
                    acl.insertAce(acl.getEntries().size(), permission, sid, true);
                }
            }
            mutableAclService.updateAcl(acl);

        } catch (Exception e) {
            throw new RuntimeException("Could not add group permissions for user", e);
        }
    }

    /**
     * @param objectIdentity
     * @return
     */
    private MutableAcl getMutableAcl(ObjectIdentity objectIdentity) {

        try {
            return (MutableAcl) mutableAclService.readAclById(objectIdentity);
        } catch (NotFoundException e) {
            log.info("Inside mutableAcl, with authentication context ... " + SecurityContextHolder.getContext().getAuthentication());
            MutableAcl acl = mutableAclService.createAcl(objectIdentity);
            return (MutableAcl) mutableAclService.readAclById(objectIdentity);

        }
    }


    /**
     * @param group
     * @param user
     * @param groupPermissions
     */
    @Override
    public void removeUserGroupPermissions(Group group, User user, Set<Permission> groupPermissions) {

        try {
            ObjectIdentityImpl objectIdentity = new ObjectIdentityImpl(Group.class, group.getId());

            MutableAcl acl = (MutableAcl) mutableAclService.readAclById(objectIdentity);
            Sid sid = new PrincipalSid(user.getUsername());

            for (Permission permission : groupPermissions) {
                deleteAce(acl, permission, sid);
            }

            acl = mutableAclService.updateAcl(acl);

            log.info("Deleted group {} ACL permissions for recipient {}", group.getGroupName(), sid);
        } catch (Exception e) {
            throw new RuntimeException("Could not remove group permissions for user", e);
        }

    }


    /**
     * @param acl
     * @param permission
     * @param sid
     */
    private void deleteAce(MutableAcl acl, Permission permission, Sid sid) {
        List<AccessControlEntry> entries = ImmutableList.copyOf(acl.getEntries());
        int index = 0;
        for (AccessControlEntry accessControlEntry : entries) {
            if (accessControlEntry.getSid().equals(sid) && accessControlEntry.getPermission().equals(permission)) {
                acl.deleteAce(index);
            }
            index++;

        }
    }


    @Override
    public boolean hasGroupPermission(Permission permission, Group group, User user) {
        try {
            ObjectIdentity objectIdentity = new ObjectIdentityImpl(Group.class, group.getId());

            ImmutableList<Sid> sids = ImmutableList.of(new PrincipalSid(user.getUsername()));
            MutableAcl acl = (MutableAcl) mutableAclService.readAclById(objectIdentity, sids);

            return !acl.getEntries().isEmpty() && acl.isGranted(ImmutableList.of(permission), sids, false);

        } catch (NotFoundException nfe) {
            log.info("Returning false - No ACLs apply for this principal");
            return false;
        }
    }

    /**
     * @param groupId
     * @param permission
     * @return Group if the permission is granted
     * @throws AccessDeniedException if the permission is NOT granted
     */
    @Override
    public Group loadGroup(Long groupId, Permission permission) {

        Group group = groupManagementService.loadGroup(groupId);

        if (group == null) {
            throw new IllegalArgumentException("Group '" + groupId + "' does not exist.");
        }

        if (!permissionEvaluator.hasPermission(SecurityContextHolder.getContext().getAuthentication(), group, permission)) {
            throw new AccessDeniedException("Unauthorised access '" + permission.getAuthority() + "' for Group '" + group.getGroupName() + "'");
        }
        return group;
    }

    /**
     * @param objectIdentity
     * @param acl
     * @param sid
     */
    private void removePermissions(MutableAcl acl, Sid sid) {
        try {

            while (hasPermissionsForRecipient(acl, sid)) {
                for (int i = 0; i < acl.getEntries().size(); i++) {
                    AccessControlEntry entry = acl.getEntries().get(i);
                    Sid aclEntrySid = entry.getSid();
                    if (aclEntrySid.equals(sid)) {
                        acl.deleteAce(i);
                    }
                }
            }
        } catch (NotFoundException e) {
            log.info("No ACL's  for user to delete permissions");
        }
    }

    private boolean hasPermissionsForRecipient(MutableAcl acl, Sid sid) {
        if (acl.getEntries() == null || acl.getEntries().isEmpty()) {
            return false;
        }

        List<AccessControlEntry> accessControlEntries = acl.getEntries();
        for (AccessControlEntry accessControlEntry : accessControlEntries) {
            if (accessControlEntry.getSid().equals(sid)) {
                return true;
            }
        }
        return false;
    }
}