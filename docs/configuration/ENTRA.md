# Microsoft Entra ID Provider

Microsoft Entra ID can be used as an OpenID Connect identity provider. The plugin can retrieve the signed-in user's profile photo from Microsoft Graph and serve it through Jenkins.

## Entra application configuration

In the Entra app registration used by Jenkins:

1. Add the required OpenID Connect redirect URI for the Jenkins instance.
2. Under **API permissions**, add Microsoft Graph **Delegated** permission `User.Read`.
3. Grant admin consent if required by your organization.

`User.Read` allows the access token issued during the Jenkins login to retrieve the signed-in user's photo from:

```text
https://graph.microsoft.com/v1.0/me/photo/$value
```

A user without a profile photo returns `404` from this endpoint. That is treated as no avatar and does not prevent login.

## Plugin configuration

Use the Entra OpenID Connect well-known endpoint when possible:

```text
https://login.microsoftonline.com/<tenant-id>/v2.0/.well-known/openid-configuration
```

Make sure the requested scopes include:

```text
openid profile email User.Read
```

For manual server configuration, add `User.Read` to the **Scopes** field. For well-known configuration, add it to **Scopes override** when an override is configured.

The plugin recognizes Microsoft Entra issuers and fetches the photo server-side after authentication. The image is exposed to Jenkins through the user's avatar endpoint; the browser never receives or sends the Graph bearer token.

## Testing

After installing or upgrading the plugin, log out and sign in again so the plugin obtains a token with the updated permission. The avatar request should be made to:

```text
/user/<username>/oic-avatar/image
```

A successful request returns `200` with an image content type such as `image/jpeg` or `image/png`. A user without a photo has no avatar and receives the normal Jenkins fallback.

If the avatar is missing, check the Jenkins log for the Microsoft Graph response status. A `401` or `403` usually means that `User.Read` was not granted or that the user has not signed in again after permission changes.
