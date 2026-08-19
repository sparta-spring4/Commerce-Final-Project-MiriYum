UPDATE platform_operator_accounts accounts
JOIN platform_operator_role_grants role_grants
  ON role_grants.platform_operator_account_id = accounts.platform_operator_account_id
 AND role_grants.role = 'ENFORCEMENT_OPERATOR'
SET accounts.authority_version = accounts.authority_version + 1,
    accounts.session_version = accounts.session_version + 1,
    accounts.row_version = accounts.row_version + 1,
    accounts.updated_at = NOW(6);
