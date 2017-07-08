package za.org.grassroot.webapp.controller.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.org.grassroot.integration.messaging.CreateJwtTokenRequest;
import za.org.grassroot.integration.messaging.JwtService;
import za.org.grassroot.webapp.enums.RestMessage;
import za.org.grassroot.webapp.model.rest.wrappers.ResponseWrapper;
import za.org.grassroot.webapp.util.RestUtil;

@RestController
@RequestMapping("/auth")
public class AuthenticationController {

    private static final Logger logger = LoggerFactory.getLogger(AuthenticationController.class);

    @Autowired
    private JwtService jwtService;

    @RequestMapping(value = "/login", method = RequestMethod.GET)
    public ResponseEntity<ResponseWrapper> login(@RequestParam("phoneNumber")String phoneNumber,
                                                 @RequestParam("password")String password,
                                                 @RequestParam("clientType") String clientType) {

        //authenticate user before issuing token
        try {

            //TODO: authenticate user before issuing token

            // Generate a token for the user
            String token = jwtService.createJwt(new CreateJwtTokenRequest());

            // Return the token on the response
            return RestUtil.okayResponseWithData(RestMessage.LOGIN_SUCCESS, token);

        } catch (Exception e) {
           logger.error("Failed to generate authentication token for:  " + phoneNumber);
            return RestUtil.errorResponse(HttpStatus.UNAUTHORIZED, RestMessage.INVALID_OTP);
        }

    }

    @RequestMapping(value = "/validateToken", method = RequestMethod.GET)
    public ResponseEntity<ResponseWrapper> validateToken(@RequestParam("token")String token) {
        boolean isJwtTokenValid = jwtService.isJwtTokenValid(token);
        if (isJwtTokenValid) {
            return RestUtil.messageOkayResponse(RestMessage.TOKEN_STILL_VALID);
        } else {
            return RestUtil.errorResponse(HttpStatus.EXPECTATION_FAILED, RestMessage.INVALID_TOKEN);
        }
    }
}
