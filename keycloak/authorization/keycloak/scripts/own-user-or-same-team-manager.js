var context = $evaluation.getContext();
var identity = context.getIdentity();
var attributes = identity.getAttributes().toMap();
var username = attributes.get('preferred_username');

// Get the request URI from claim-information-point
var ctxAttributes = context.getAttributes().toMap();
var requestUri = ctxAttributes.get('request-uri');
if (requestUri == null) {
    requestUri = ctxAttributes.get('kc.client.network.request-uri');
}

if (requestUri != null && !requestUri.isEmpty() && username != null && !username.isEmpty()) {
    // Extract target username from URI: /users/{username}
    var uri = requestUri.iterator().next();
    var parts = uri.split('/');
    var targetUsername = parts.length >= 3 ? parts[2] : null;
    var currentUser = username.iterator().next();

    if (targetUsername != null && currentUser.equals(targetUsername)) {
        // Own profile - always allowed
        $evaluation.grant();
    } else if (targetUsername != null) {
        // Check if current user is a manager in the same team
        var realm = $evaluation.getRealm();
        var isManager = realm.isUserInRealmRole(currentUser, 'manager');

        if (isManager) {
            // Realm methods accept id, username or email
            var currentUserAttrs = realm.getUserAttributes(currentUser);
            var targetUserAttrs = realm.getUserAttributes(targetUsername);

            var currentUserTeam = currentUserAttrs.get('team');
            var targetUserTeam = targetUserAttrs.get('team');

            if (currentUserTeam != null && !currentUserTeam.isEmpty() &&
                targetUserTeam != null && !targetUserTeam.isEmpty() &&
                currentUserTeam.get(0).equals(targetUserTeam.get(0))) {
                $evaluation.grant();
            }
        }
    }
}
