package com.miriyum.architecture;

import org.springframework.web.bind.annotation.RequestMethod;

record ApiRoute(RequestMethod method, String path) {
}
