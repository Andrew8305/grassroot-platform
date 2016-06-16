package za.org.grassroot.core.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import za.org.grassroot.core.domain.Group;
import za.org.grassroot.core.domain.LogBook;
import za.org.grassroot.core.domain.User;

import java.time.Instant;
import java.util.List;

/**
 * Created by aakilomar on 12/5/15.
 */
public interface LogBookRepository extends JpaRepository<LogBook, Long> {

    LogBook findOneByUid(String uid);

    /*
    Retrieve all logbook entries for all the groups of a particular user
     */
    List<LogBook> findByParentGroupMembershipsUserAndActionByDateGreaterThan(User user, Instant start);
    List<LogBook> findByParentGroupMembershipsUserAndActionByDateBetweenAndCompleted(User user, Instant start, Instant end, boolean completed, Sort sort);
    Page<LogBook> findByParentGroupMembershipsUserAndCompletedOrderByActionByDateDesc(User user, boolean completed, Pageable pageable);
    int countByParentGroupMembershipsUserAndActionByDateBetweenAndCompleted(User user, Instant start, Instant end, boolean completed);

    /*
    Retrieve all logbook entries assigned to a particular user
     */
    List<LogBook> findByAssignedMembersAndActionByDateGreaterThan(User user, Instant start);
    List<LogBook> findByAssignedMembersAndActionByDateBetweenAndCompleted(User user, Instant start, Instant end, boolean completed, Sort sort);

    /*
    Retrieve logbook entries for a group (with variants)
     */
    List<LogBook> findByParentGroup(Group group);
    List<LogBook> findByParentGroupAndCreatedDateTimeBetween(Group group, Instant start, Instant end, Sort sort);
    List<LogBook> findByParentGroupAndMessageAndCreatedDateTime(Group group, String message, Instant createdDateTime);

    List<LogBook> findByParentGroupAndActionByDateGreaterThan(Group group, Instant dueDate);
    List<LogBook> findByParentGroupAndCompletedAndActionByDateGreaterThan(Group group, boolean completed, Instant dueDate);
    Page<LogBook> findByParentGroupAndCompletedOrderByActionByDateDesc(Group group, boolean completed, Pageable pageable);

    List<LogBook> findByReplicatedGroupAndMessageAndActionByDateOrderByParentGroupIdAsc(Group replicatedGroup, String message, Instant actionByDateTime);

    // methods for analyzing logbooks (for admin)
    Long countByCreatedDateTimeBetween(Instant start, Instant end);

    @Transactional
    @Query(value = "select * from log_book l where l.action_by_date is not null and l.completed = false and l.number_of_reminders_left_to_send > 0 and (l.action_by_date + l.reminder_minutes * INTERVAL '1 minute') < current_timestamp", nativeQuery = true)
    List<LogBook> findAllLogBooksForReminding();

    @Query(value = "select count(l) from LogBook l where l.replicatedGroup=?1 and l.message=?2 and l.actionByDate=?3")
    int countReplicatedEntries(Group group, String message, Instant actionByDate);

}
