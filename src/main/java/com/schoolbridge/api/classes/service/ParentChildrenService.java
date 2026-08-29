package com.schoolbridge.api.classes.service;

import com.schoolbridge.api.classes.dto.ParentChildResponse;
import java.util.List;
import java.util.UUID;

public interface ParentChildrenService {

  List<ParentChildResponse> listChildren(UUID parentUserId);
}

