package za.org.grassroot.services;

import za.org.grassroot.core.domain.SafetyEvent;
import za.org.grassroot.core.domain.User;

import java.util.List;

/**
 * Created by paballo on 2016/07/18.
 */
public interface SafetyEventBroker {

    SafetyEvent create(String userUid, String groupUid);

    SafetyEvent load(String safetyEventUid);

    void recordResponse(String userUid, String safetyEventUid, boolean isValid);

    List<SafetyEvent> fetchGroupSafetyEvents(String groupUid);

    List<SafetyEvent> getOutstandingUserSafetyEventsResponse(String userUid);

    boolean isUserBarred(String uid);

    void sendReminders(String uid);

    boolean needsToRespondToSafetyEvent(User sessionUser);

}
