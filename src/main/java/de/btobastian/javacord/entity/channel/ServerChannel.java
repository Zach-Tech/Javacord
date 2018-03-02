package de.btobastian.javacord.entity.channel;

import com.fasterxml.jackson.databind.JsonNode;
import de.btobastian.javacord.ImplDiscordApi;
import de.btobastian.javacord.entity.permission.PermissionState;
import de.btobastian.javacord.entity.permission.PermissionType;
import de.btobastian.javacord.entity.permission.Permissions;
import de.btobastian.javacord.entity.permission.PermissionsBuilder;
import de.btobastian.javacord.entity.permission.Role;
import de.btobastian.javacord.entity.permission.impl.ImplPermissions;
import de.btobastian.javacord.entity.server.Server;
import de.btobastian.javacord.entity.server.invite.InviteBuilder;
import de.btobastian.javacord.entity.server.invite.RichInvite;
import de.btobastian.javacord.entity.server.invite.impl.ImplInvite;
import de.btobastian.javacord.entity.user.User;
import de.btobastian.javacord.listener.ChannelAttachableListener;
import de.btobastian.javacord.listener.ObjectAttachableListener;
import de.btobastian.javacord.listener.channel.server.ServerChannelAttachableListener;
import de.btobastian.javacord.listener.channel.server.ServerChannelChangeNameListener;
import de.btobastian.javacord.listener.channel.server.ServerChannelChangeOverwrittenPermissionsListener;
import de.btobastian.javacord.listener.channel.server.ServerChannelChangePositionListener;
import de.btobastian.javacord.listener.channel.server.ServerChannelDeleteListener;
import de.btobastian.javacord.util.ClassHelper;
import de.btobastian.javacord.util.event.ListenerManager;
import de.btobastian.javacord.util.rest.RestEndpoint;
import de.btobastian.javacord.util.rest.RestMethod;
import de.btobastian.javacord.util.rest.RestRequest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class represents a server channel.
 */
public interface ServerChannel extends Channel {

    /**
     * Gets the name of the channel.
     *
     * @return The name of the channel.
     */
    String getName();

    /**
     * Gets the server of the channel.
     *
     * @return The server of the channel.
     */
    Server getServer();

    /**
     * Gets the raw position of the channel.
     * This is the positions sent from Discord and might not be unique and have gaps.
     * Also every channel type (text, voice and category) has its own position counter.
     *
     * @return The raw position of the channel.
     */
    int getRawPosition();

    /**
     * Gets the real position of the channel.
     * Returns <code>-1</code> if the channel is deleted.
     *
     * @return The real position of the channel.
     */
    default int getPosition() {
        return getServer().getChannels().indexOf(this);
    }

    /**
     * Gets an invite builder for this channel.
     *
     * @return An invite builder for this channel.
     */
    default InviteBuilder getInviteBuilder() {
        return new InviteBuilder(this);
    }

    /**
     * Gets the invites of the server.
     *
     * @return The invites of the server.
     */
    default CompletableFuture<Collection<RichInvite>> getInvites() {
        return new RestRequest<Collection<RichInvite>>(getApi(), RestMethod.GET, RestEndpoint.CHANNEL_INVITE)
                .setUrlParameters(getIdAsString())
                .execute(result -> {
                    Collection<RichInvite> invites = new HashSet<>();
                    for (JsonNode inviteJson : result.getJsonBody()) {
                        invites.add(new ImplInvite(getApi(), inviteJson));
                    }
                    return Collections.unmodifiableCollection(invites);
                });
    }

    /**
     * Create an updater for this channel.
     *
     * @return An updater for this channel.
     */
    ServerChannelUpdater createUpdater();

    /**
     * Updates the name of the channel.
     * <p>
     * If you want to update several settings at once, it's recommended to use the
     * {@link ServerChannelUpdater} from {@link #createUpdater()} which provides a better performance!
     *
     * @param name The new name of the channel.
     * @return A future to check if the update was successful.
     */
    default CompletableFuture<Void> updateName(String name) {
        return createUpdater().setName(name).update();
    }

    /**
     * Updates the raw position of the channel.
     * <p>
     * If you want to update several settings at once, it's recommended to use the
     * {@link ServerChannelUpdater} from {@link #createUpdater()} which provides a better performance!
     *
     * @param rawPosition The new position of the channel.
     *                    If you want to update the position based on other channels, make sure to use
     *                    {@link ServerChannel#getRawPosition()} instead of {@link ServerChannel#getPosition()}!
     * @return A future to check if the update was successful.
     */
    default CompletableFuture<Void> updateRawPosition(int rawPosition) {
        return createUpdater().setRawPosition(rawPosition).update();
    }

    /**
     * Gets the overwritten permissions of a user in this channel.
     *
     * @param user The user.
     * @return The overwritten permissions of a user.
     */
    Permissions getOverwrittenPermissions(User user);

    /**
     * Gets the overwritten permissions of a role in this channel.
     *
     * @param role The role.
     * @return The overwritten permissions of a role.
     */
    Permissions getOverwrittenPermissions(Role role);

    /**
     * Gets the effective overwritten permissions of a user.
     * This method also takes into account the roles of the user.
     * It doesn't take into account the "global" permissions!
     *
     * @param user The user.
     * @return The effective overwritten permissions of the user.
     */
    default Permissions getEffectiveOverwrittenPermissions(User user) {
        PermissionsBuilder builder = new PermissionsBuilder(ImplPermissions.EMPTY_PERMISSIONS);
        Server server = getServer();
        Role everyoneRole = server.getEveryoneRole();
        Permissions everyoneRolePermissionOverwrites = getOverwrittenPermissions(everyoneRole);
        for (PermissionType type : PermissionType.values()) {
            if (everyoneRolePermissionOverwrites.getState(type) == PermissionState.DENIED) {
                builder.setState(type, PermissionState.DENIED);
            }
            if (everyoneRolePermissionOverwrites.getState(type) == PermissionState.ALLOWED) {
                builder.setState(type, PermissionState.ALLOWED);
            }
        }
        List<Role> rolesOfUser = new ArrayList<>(server.getRolesOf(user));
        rolesOfUser.remove(everyoneRole);
        List<Permissions> permissionOverwrites = rolesOfUser.stream()
                .map(this::getOverwrittenPermissions)
                .collect(Collectors.toList());
        for (Permissions permissions : permissionOverwrites) {
            for (PermissionType type : PermissionType.values()) {
                if (permissions.getState(type) == PermissionState.DENIED) {
                    builder.setState(type, PermissionState.DENIED);
                }
            }
        }
        for (Permissions permissions : permissionOverwrites) {
            for (PermissionType type : PermissionType.values()) {
                if (permissions.getState(type) == PermissionState.ALLOWED) {
                    builder.setState(type, PermissionState.ALLOWED);
                }
            }
        }
        for (PermissionType type : PermissionType.values()) {
            Permissions permissions = getOverwrittenPermissions(user);
            if (permissions.getState(type) == PermissionState.DENIED) {
                builder.setState(type, PermissionState.DENIED);
            }
            if (permissions.getState(type) == PermissionState.ALLOWED) {
                builder.setState(type, PermissionState.ALLOWED);
            }
        }
        return builder.build();
    }

    /**
     * Gets the effective permissions of a user in this channel.
     * The returned permission object will only have {@link PermissionState#ALLOWED} and
     * {@link PermissionState#DENIED} states!
     * It takes into account global permissions and the effective overwritten permissions of a user.
     * Remember, that some permissions affect others!
     * E.g. a user who has {@link PermissionType#SEND_MESSAGES} but not {@link PermissionType#READ_MESSAGES} cannot
     * send messages, even though he has the {@link PermissionType#SEND_MESSAGES} permission.
     *
     * @param user The user.
     * @return The effective permissions of the user in this channel.
     */
    default Permissions getEffectivePermissions(User user) {
        if (getServer().getOwner() == user) {
            return getServer().getPermissionsOf(user);
        }
        PermissionsBuilder builder = new PermissionsBuilder(getServer().getPermissionsOf(user));
        Permissions effectiveOverwrittenPermissions = getEffectiveOverwrittenPermissions(user);
        Arrays.stream(PermissionType.values())
                .filter(type -> effectiveOverwrittenPermissions.getState(type) != PermissionState.NONE)
                .forEachOrdered(type -> builder.setState(type, effectiveOverwrittenPermissions.getState(type)));
        Arrays.stream(PermissionType.values())
                .filter(type -> builder.getState(type) == PermissionState.NONE)
                .forEachOrdered(type -> builder.setState(type, PermissionState.DENIED));
        return builder.build();
    }

    /**
     * Gets the effective allowed permissions of a user in this channel.
     * It takes into account global permissions and the effective overwritten permissions of a user.
     * Remember, that some permissions affect others!
     * E.g. a user who has {@link PermissionType#SEND_MESSAGES} but not {@link PermissionType#READ_MESSAGES} cannot
     * send messages, even though he has the {@link PermissionType#SEND_MESSAGES} permission.
     *
     * @param user The user.
     * @return The effective allowed permissions of a user in this channel.
     */
    default Collection<PermissionType> getEffectiveAllowedPermissions(User user) {
        return getEffectivePermissions(user).getAllowedPermission();
    }

    /**
     * Gets the effective denied permissions of a user in this channel.
     * It takes into account global permissions and the effective overwritten permissions of a user.
     * Remember, that some permissions affect others!
     * E.g. a user who has {@link PermissionType#SEND_MESSAGES} but not {@link PermissionType#READ_MESSAGES} cannot
     * send messages, even though he has the {@link PermissionType#SEND_MESSAGES} permission.
     *
     * @param user The user.
     * @return The effective denied permissions of a user in this channel.
     */
    default Collection<PermissionType> getEffectiveDeniedPermissions(User user) {
        return getEffectivePermissions(user).getDeniedPermissions();
    }

    /**
     * Checks if the user has a given set of permissions.
     *
     * @param user The user to check.
     * @param type The permission type(s) to check.
     * @return Whether the user has all given permissions or not.
     * @see #getEffectiveAllowedPermissions(User)
     */
    default boolean hasPermissions(User user, PermissionType... type) {
        return getEffectiveAllowedPermissions(user).containsAll(Arrays.asList(type));
    }

    /**
     * Checks if the user has any of a given set of permissions.
     *
     * @param user The user to check.
     * @param type The permission type(s) to check.
     * @return Whether the user has any of the given permissions or not.
     * @see #getEffectiveAllowedPermissions(User)
     */
    default boolean hasAnyPermission(User user, PermissionType... type) {
        return getEffectiveAllowedPermissions(user).stream().anyMatch(allowedPermissionType -> Arrays.stream(type).anyMatch(allowedPermissionType::equals));
    }

    /**
     * Checks if a user has a given permission.
     * Remember, that some permissions affect others!
     * E.g. a user who has {@link PermissionType#SEND_MESSAGES} but not {@link PermissionType#READ_MESSAGES} cannot
     * send messages, even though he has the {@link PermissionType#SEND_MESSAGES} permission.
     *
     * @param user The user.
     * @param permission The permission to check.
     * @return Whether the user has the permission or not.
     */
    default boolean hasPermission(User user, PermissionType permission) {
        return getEffectiveAllowedPermissions(user).contains(permission);
    }

    /**
     * Deletes the channel.
     *
     * @return A future to tell us if the deletion was successful.
     */
    default CompletableFuture<Void> delete() {
        return delete(null);
    }

    /**
     * Deletes the channel.
     *
     * @param reason The audit log reason for the deletion.
     * @return A future to tell us if the deletion was successful.
     */
    default CompletableFuture<Void> delete(String reason) {
        return new RestRequest<Void>(getApi(), RestMethod.DELETE, RestEndpoint.CHANNEL)
                .setUrlParameters(getIdAsString())
                .setAuditLogReason(reason)
                .execute(result -> null);
    }

    /**
     * Checks if the given user can create an instant invite to this channel.
     *
     * @param user The user to check.
     * @return Whether the given user can create an instant invite or not.
     */
    default boolean canCreateInstantInvite(User user) {
        // The user must be able to see the channel
        if (!canSee(user)) {
            return false;
        }
        // You cannot create invites for categories
        if (getType() == ChannelType.CHANNEL_CATEGORY) {
            return false;
        }
        // The user must be admin or have the CREATE_INSTANT_INVITE permission
        return hasAnyPermission(user,
                                PermissionType.ADMINISTRATOR,
                                PermissionType.CREATE_INSTANT_INVITE);
    }

    /**
     * Checks if the user of the connected account can create an instant invite to this channel.
     *
     * @return Whether the user of the connected account can create an instant invite or not.
     */
    default boolean canYouCreateInstantInvite() {
        return canCreateInstantInvite(getApi().getYourself());
    }

    /**
     * Adds a listener, which listens to this channel being deleted.
     *
     * @param listener The listener to add.
     * @return The manager of the listener.
     */
    default ListenerManager<ServerChannelDeleteListener> addServerChannelDeleteListener(
            ServerChannelDeleteListener listener) {
        return ((ImplDiscordApi) getApi()).addObjectListener(
                ServerChannel.class, getId(), ServerChannelDeleteListener.class, listener);
    }

    /**
     * Gets a list with all registered server channel delete listeners.
     *
     * @return A list with all registered server channel delete listeners.
     */
    default List<ServerChannelDeleteListener> getServerChannelDeleteListeners() {
        return ((ImplDiscordApi) getApi()).getObjectListeners(
                ServerChannel.class, getId(), ServerChannelDeleteListener.class);
    }

    /**
     * Adds a listener, which listens to this server channel name changes.
     *
     * @param listener The listener to add.
     * @return The manager of the listener.
     */
    default ListenerManager<ServerChannelChangeNameListener> addServerChannelChangeNameListener(
            ServerChannelChangeNameListener listener) {
        return ((ImplDiscordApi) getApi()).addObjectListener(
                ServerChannel.class, getId(), ServerChannelChangeNameListener.class, listener);
    }

    /**
     * Gets a list with all registered server channel change name listeners.
     *
     * @return A list with all registered server channel change name listeners.
     */
    default List<ServerChannelChangeNameListener> getServerChannelChangeNameListeners() {
        return ((ImplDiscordApi) getApi()).getObjectListeners(
                ServerChannel.class, getId(), ServerChannelChangeNameListener.class);
    }

    /**
     * Adds a listener, which listens this server channel position changes.
     *
     * @param listener The listener to add.
     * @return The manager of the listener.
     */
    default ListenerManager<ServerChannelChangePositionListener> addServerChannelChangePositionListener(
            ServerChannelChangePositionListener listener) {
        return ((ImplDiscordApi) getApi()).addObjectListener(
                ServerChannel.class, getId(), ServerChannelChangePositionListener.class, listener);
    }

    /**
     * Gets a list with all registered server channel change position listeners.
     *
     * @return A list with all registered server channel change position listeners.
     */
    default List<ServerChannelChangePositionListener> getServerChannelChangePositionListeners() {
        return ((ImplDiscordApi) getApi()).getObjectListeners(
                ServerChannel.class, getId(), ServerChannelChangePositionListener.class);
    }

    /**
     * Adds a listener, which listens to overwritten permission changes of this server.
     *
     * @param listener The listener to add.
     * @return The manager of the listener.
     */
    default ListenerManager<ServerChannelChangeOverwrittenPermissionsListener>
    addServerChannelChangeOverwrittenPermissionsListener(ServerChannelChangeOverwrittenPermissionsListener listener) {
        return ((ImplDiscordApi) getApi()).addObjectListener(
                ServerChannel.class, getId(), ServerChannelChangeOverwrittenPermissionsListener.class, listener);
    }

    /**
     * Gets a list with all registered server channel change overwritten permissions listeners.
     *
     * @return A list with all registered server channel change overwritten permissions listeners.
     */
    default List<ServerChannelChangeOverwrittenPermissionsListener>
    getServerChannelChangeOverwrittenPermissionsListeners() {
        return ((ImplDiscordApi) getApi()).getObjectListeners(
                ServerChannel.class, getId(), ServerChannelChangeOverwrittenPermissionsListener.class);
    }

    /**
     * Adds a listener that implements one or more {@code ServerChannelAttachableListener}s.
     * Adding a listener multiple times will only add it once
     * and return the same listener managers on each invocation.
     * The order of invocation is according to first addition.
     *
     * @param listener The listener to add.
     * @param <T> The type of the listener.
     * @return The managers for the added listener.
     */
    @SuppressWarnings("unchecked")
    default <T extends ServerChannelAttachableListener & ObjectAttachableListener>
    Collection<ListenerManager<? extends ServerChannelAttachableListener>>
    addServerChannelAttachableListener(T listener) {
        return ClassHelper.getInterfacesAsStream(listener.getClass())
                .filter(ServerChannelAttachableListener.class::isAssignableFrom)
                .filter(ObjectAttachableListener.class::isAssignableFrom)
                .map(listenerClass -> (Class<T>) listenerClass)
                .flatMap(listenerClass -> {
                    if (ChannelAttachableListener.class.isAssignableFrom(listenerClass)) {
                        return addChannelAttachableListener(
                                (ChannelAttachableListener & ObjectAttachableListener) listener).stream();
                    } else {
                        return Stream.of(((ImplDiscordApi) getApi()).addObjectListener(ServerChannel.class, getId(),
                                                                                       listenerClass, listener));
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * Removes a listener that implements one or more {@code ServerChannelAttachableListener}s.
     *
     * @param listener The listener to remove.
     * @param <T> The type of the listener.
     */
    @SuppressWarnings("unchecked")
    default <T extends ServerChannelAttachableListener & ObjectAttachableListener> void
    removeServerChannelAttachableListener(T listener) {
        ClassHelper.getInterfacesAsStream(listener.getClass())
                .filter(ServerChannelAttachableListener.class::isAssignableFrom)
                .filter(ObjectAttachableListener.class::isAssignableFrom)
                .map(listenerClass -> (Class<T>) listenerClass)
                .forEach(listenerClass -> {
                    if (ChannelAttachableListener.class.isAssignableFrom(listenerClass)) {
                        removeChannelAttachableListener(
                                (ChannelAttachableListener & ObjectAttachableListener) listener);
                    } else {
                        ((ImplDiscordApi) getApi()).removeObjectListener(ServerChannel.class, getId(),
                                                                         listenerClass, listener);
                    }
                });
    }

    /**
     * Gets a map with all registered listeners that implement one or more {@code ServerChannelAttachableListener}s and
     * their assigned listener classes they listen to.
     *
     * @param <T> The type of the listeners.
     * @return A map with all registered listeners that implement one or more {@code ServerChannelAttachableListener}s
     * and their assigned listener classes they listen to.
     */
    @SuppressWarnings("unchecked")
    default <T extends ServerChannelAttachableListener & ObjectAttachableListener> Map<T, List<Class<T>>>
    getServerChannelAttachableListeners() {
        Map<T, List<Class<T>>> serverChannelListeners =
                ((ImplDiscordApi) getApi()).getObjectListeners(ServerChannel.class, getId());
        getChannelAttachableListeners().forEach((listener, listenerClasses) -> serverChannelListeners
                .merge((T) listener,
                       (List<Class<T>>) (Object) listenerClasses,
                       (listenerClasses1, listenerClasses2) -> {
                           listenerClasses1.addAll(listenerClasses2);
                           return listenerClasses1;
                       }));
        return serverChannelListeners;
    }

    /**
     * Removes a listener from this server channel.
     *
     * @param listenerClass The listener class.
     * @param listener The listener to remove.
     * @param <T> The type of the listener.
     */
    default <T extends ServerChannelAttachableListener & ObjectAttachableListener> void removeListener(
            Class<T> listenerClass, T listener) {
        ((ImplDiscordApi) getApi()).removeObjectListener(ServerChannel.class, getId(), listenerClass, listener);
    }

    @Override
    default Optional<? extends ServerChannel> getCurrentCachedInstance() {
        return getApi().getServerById(getServer().getId()).flatMap(server -> server.getChannelById(getId()));
    }

    @Override
    default CompletableFuture<? extends ServerChannel> getLatestInstance() {
        Optional<? extends ServerChannel> currentCachedInstance = getCurrentCachedInstance();
        if (currentCachedInstance.isPresent()) {
            return CompletableFuture.completedFuture(currentCachedInstance.get());
        } else {
            CompletableFuture<? extends ServerChannel> result = new CompletableFuture<>();
            result.completeExceptionally(new NoSuchElementException());
            return result;
        }
    }

}