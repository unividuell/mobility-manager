-- Every stored session holds the old DefaultOAuth2User principal, which the auth lib's AuthPrincipal
-- replaces. Left in place, each would answer 500 instead of the login page; this way everyone signs
-- in once more. SPRING_SESSION_ATTRIBUTES follows through ON DELETE CASCADE.
DELETE FROM SPRING_SESSION;
