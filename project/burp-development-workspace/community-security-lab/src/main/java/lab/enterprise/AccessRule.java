package lab.enterprise;

import java.lang.annotation.*;

/** Every community API declares a server-side permission, including public APIs. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AccessRule {
    CommunityAccessPolicy.Permission value();
}
