package za.org.grassroot.meeting_organizer.model;

/**
 * Created by luke on 2015/07/16.
 * Lots of to-dos, principally: build the "created_by_user" relationship; add list of users (or user ids); hash code etc
 */

import java.sql.Timestamp;
import javax.persistence.Basic;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name="\"group\"") // quoting table name in case "group" is a reserved keyword
public class Group {
    private String groupName;
    private Integer id;
    private Timestamp createdDateTime;

    @Basic
    @Column(name = "name", nullable = false, length = 50)
    public String getGroupName() { return groupName; }

    public void setGroupName(String groupName) { this.groupName = groupName; }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name="id", nullable = false)
    public Integer getId() { return id; }

    public void setId(Integer id) { this.id = id; }

    @Basic
    @Column(name="created_date_time", insertable = false, updatable = false)
    public Timestamp getCreatedDateTime() { return createdDateTime; }

    public void setCreatedDateTime(Timestamp createdDateTime) { this.createdDateTime = createdDateTime; }

    // To do: add hash tag method, analogous to User

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }

        final Group group = (Group) o;

        if (createdDateTime != null ? !createdDateTime.equals(group.createdDateTime) : group.createdDateTime != null) { return false; }
        if (id != null ? !id.equals(group.id) : group.id != null) { return false; }
        if (groupName != null ? !groupName.equals(group.groupName) : group.groupName != null) { return false; }

        return true;
    }
}
