package com.example.springrestful.dto;

import com.example.springrestful.enums.UserRole;
import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class UserResponse {

    private Long id;
    private String email;
    private String username;
    private Set<UserRole> roles;
    private boolean emailVerified;
    private boolean twoFactorAuthEnabled;
    private int managedEmployeesCount;
}