package za.org.grassroot.webapp.model.rest.ResponseWrappers;

import org.springframework.http.HttpStatus;
import za.org.grassroot.webapp.enums.RestMessage;
import za.org.grassroot.webapp.enums.RestStatus;

/**
 * Created by paballo on 2016/03/07.
 */
public class UserResponseWrapper implements ResponseWrapper {

    private String status;
    private int code;
    private String message;

    public UserResponseWrapper(){}

    public UserResponseWrapper(HttpStatus code, RestMessage message, RestStatus status) {
        this.code = code.value();
        this.message = String.valueOf(message);
        this.status = String.valueOf(status);
    }
    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public String getStatus() {
        return status;
    }

    @Override
    public int getCode() {
        return code;
    }


}
