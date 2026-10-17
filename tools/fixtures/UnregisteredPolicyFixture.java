package lab.enterprise;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Compiled only into the HTTP harness, never packaged into the site. */
@RestController
public class UnregisteredPolicyFixture {
    @GetMapping("/api/workspace/fixed/policy-unregistered")
    public Map<String,Object> unregistered() { return Map.of("mustNeverBeReturned",true); }
}
