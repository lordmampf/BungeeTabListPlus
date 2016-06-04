/*
 * BungeeTabListPlus - a BungeeCord plugin to customize the tablist
 *
 * Copyright (C) 2014 - 2015 Florian Stober
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package codecrafter47.bungeetablistplus.tablisthandler.logic;

import codecrafter47.bungeetablistplus.api.bungee.Skin;
import codecrafter47.bungeetablistplus.managers.SkinManagerImpl;
import codecrafter47.bungeetablistplus.protocol.PacketListenerResult;
import codecrafter47.bungeetablistplus.skin.PlayerSkin;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.packet.PlayerListHeaderFooter;
import net.md_5.bungee.protocol.packet.PlayerListItem;
import net.md_5.bungee.protocol.packet.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.Math.min;
import static net.md_5.bungee.protocol.packet.PlayerListItem.Action.ADD_PLAYER;
import static net.md_5.bungee.protocol.packet.PlayerListItem.Action.REMOVE_PLAYER;
import static net.md_5.bungee.protocol.packet.PlayerListItem.Action.UPDATE_DISPLAY_NAME;
import static net.md_5.bungee.protocol.packet.PlayerListItem.Action.UPDATE_GAMEMODE;
import static net.md_5.bungee.protocol.packet.PlayerListItem.Action.UPDATE_LATENCY;

public abstract class AbstractTabListLogic extends TabListHandler {

	protected static final String[] fakePlayerUsernames = new String[80];
	protected static final UUID[] fakePlayerUUIDs = new UUID[80];
	private static final Set<String> fakePlayerUsernameSet;
	private static final Set<UUID> fakePlayerUUIDSet;

	private static boolean teamCollisionRuleSupported;

	static {
		for (int i = 0; i < 80; i++) {
			fakePlayerUsernames[i] = String.format(" BTLP Slot %02d", i);
			fakePlayerUUIDs[i] = UUID.nameUUIDFromBytes(("OfflinePlayer:" + fakePlayerUsernames[i]).getBytes(Charsets.UTF_8));
		}
		fakePlayerUsernameSet = ImmutableSet.copyOf(fakePlayerUsernames);
		fakePlayerUUIDSet = ImmutableSet.copyOf(fakePlayerUUIDs);

		try {
			Team.class.getDeclaredMethod("setCollisionRule", String.class);
			teamCollisionRuleSupported = true;
		} catch (NoSuchMethodException e) {
			teamCollisionRuleSupported = false;
		}
	}

	protected final Map<UUID, TabListItem> serverTabList = new ConcurrentHashMap<>();
	protected String serverHeader = null;
	protected String serverFooter = null;

	protected final Map<String, net.md_5.bungee.api.score.Team> serverTeams = new HashMap<>();
	protected final Map<String, String> playerToTeamMap = new HashMap<>();
	protected final Map<String, Integer> nameToSlotMap = new HashMap<>();

	protected Multimap<UUID, Integer> skinUuidToSlotMap = MultimapBuilder.hashKeys().linkedListValues().build();
	protected TObjectIntMap<UUID> uuidToSlotMap = new TObjectIntHashMap<>();

	protected UUID[] clientUuid = new UUID[80];
	protected String[] clientUsername = new String[80];
	protected PlayerSkin[] clientSkin = new PlayerSkin[80];
	protected String[] clientText = new String[80];
	protected int[] clientPing = new int[80];
	protected String clientHeader = null;
	protected String clientFooter = null;

	protected int size = 0;

	protected boolean passtrough = true;

	public AbstractTabListLogic(TabListHandler parent) {
		super(parent);
	}

	abstract protected UUID getUniqueId();

	abstract protected void sendPacket(DefinedPacket packet);

	@Override
	public void onConnected() {
		// add our teams to the client
		for (int i = 0; i < 80; i++) {
			Team team = new Team();
			team.setMode((byte) 0);
			team.setName(fakePlayerUsernames[i]);
			team.setDisplayName(fakePlayerUsernames[i]);
			team.setPrefix("");
			team.setSuffix("");
			team.setFriendlyFire((byte) 1);
			team.setNameTagVisibility("ALWAYS");
			if (teamCollisionRuleSupported) {
				team.setCollisionRule("ALWAYS");
			}
			team.setColor((byte) 0);
			team.setPlayers(new String[0]);
			sendPacket(team);
		}
	}

	@Override
	public void onDisconnected() {
		// to nothing
	}

	@Override
	public PacketListenerResult onPlayerListPacket(PlayerListItem packet) {

		// update server tab list
		switch (packet.getAction()) {
		case ADD_PLAYER:
			for (PlayerListItem.Item item : packet.getItems()) {
				if (fakePlayerUUIDSet.contains(item.getUuid())) {
					throw new AssertionError("UUID collision: " + item);
				}
				if (fakePlayerUsernameSet.contains(item.getUsername())) {
					throw new AssertionError("Username collision: " + item);
				}
				serverTabList.put(item.getUuid(), new TabListItem(item));
			}
			break;
		case UPDATE_GAMEMODE:
			for (PlayerListItem.Item item : packet.getItems()) {
				TabListItem tabListItem = serverTabList.get(item.getUuid());
				if (tabListItem != null) {
					tabListItem.setGamemode(item.getGamemode());
				}
			}
			break;
		case UPDATE_LATENCY:
			for (PlayerListItem.Item item : packet.getItems()) {
				TabListItem tabListItem = serverTabList.get(item.getUuid());
				if (tabListItem != null) {
					tabListItem.setPing(item.getPing());
				}
			}
			break;
		case UPDATE_DISPLAY_NAME:
			for (PlayerListItem.Item item : packet.getItems()) {
				TabListItem tabListItem = serverTabList.get(item.getUuid());
				if (tabListItem != null) {
					tabListItem.setDisplayName(item.getDisplayName());
				}
			}
			break;
		case REMOVE_PLAYER:
			for (PlayerListItem.Item item : packet.getItems()) {
				serverTabList.remove(item.getUuid());
			}
			break;
		}

		// resize if necessary
		if (serverTabList.size() > size) {
			setSizeInternal(min(((serverTabList.size() + 19) / 20) * 20, 80));
		}

		// if passthrough is enabled send the packet to the client
		if (passtrough || size == 80) {
			sendPacket(packet);
			return PacketListenerResult.CANCEL;
		}

		// do magic
		switch (packet.getAction()) {
		case ADD_PLAYER:
			for (PlayerListItem.Item item : packet.getItems()) {
				if (item.getGamemode() == 3 && item.getUuid().equals(getUniqueId())) {

					if (uuidToSlotMap.containsKey(item.getUuid())) {
						int slot = uuidToSlotMap.get(item.getUuid());

						if (slot != size - 1) {
							// player changed to gm 3
							useFakePlayerForSlot(slot);
							slot = size - 1;

							if (clientUuid[slot] != fakePlayerUUIDs[slot]) {
								// needs to be moved
								int targetSlot = findSlotForPlayer(clientUuid[slot]);
								useRealPlayerForSlot(targetSlot, clientUuid[slot]);
							}

							useRealPlayerForSlot(slot, item.getUuid());
						} else {
							// player in gm 3 updates username + skin
							useRealPlayerForSlot(slot, item.getUuid());
						}
					} else {
						// player joined with gm 3
						int slot = size - 1;

						if (clientUuid[slot] != fakePlayerUUIDs[slot]) {
							// needs to be moved
							int targetSlot = findSlotForPlayer(clientUuid[slot]);
							useRealPlayerForSlot(targetSlot, clientUuid[slot]);
						}

						useRealPlayerForSlot(slot, item.getUuid());
					}
				} else {
					item.setGamemode(0);

					if (uuidToSlotMap.containsKey(item.getUuid()) && skinUuidToSlotMap.containsKey(item.getUuid())
							&& !skinUuidToSlotMap.get(item.getUuid()).contains(uuidToSlotMap.get(item.getUuid()))) {
						// player that was not in correct position updates username + skin
						// probably changed away from gm 3
						// move the player slot if he changed await from gm 3
						useFakePlayerForSlot(uuidToSlotMap.get(item.getUuid()));
					}

					if (uuidToSlotMap.containsKey(item.getUuid())) {
						// player is already in the tab list, just update
						// skin and user name
						useRealPlayerForSlot(uuidToSlotMap.get(item.getUuid()), item.getUuid());
					} else {
						// player isn't yet in the tab list
						int slot = findSlotForPlayer(item.getUuid());
						useRealPlayerForSlot(slot, item.getUuid());
					}
				}
			}
			break;
		case UPDATE_GAMEMODE:
			for (PlayerListItem.Item item : packet.getItems()) {
				if (item.getUuid().equals(getUniqueId())) {
					int slot = uuidToSlotMap.get(item.getUuid());

					if (item.getGamemode() == 3 && slot != size - 1) {
						// player changed to gm 3
						useFakePlayerForSlot(slot);
						slot = size - 1;

						if (clientUuid[slot] != fakePlayerUUIDs[slot]) {
							// needs to be moved
							int targetSlot = findSlotForPlayer(clientUuid[slot]);
							useRealPlayerForSlot(targetSlot, clientUuid[slot]);
						}

						useRealPlayerForSlot(slot, item.getUuid());
					} else if (item.getGamemode() != 3 && slot == size - 1) {
						useFakePlayerForSlot(size - 1);
						useRealPlayerForSlot(findSlotForPlayer(getUniqueId()), getUniqueId());
					}
				}
			}
			break;
		case REMOVE_PLAYER:
			sendPacket(packet);
			for (PlayerListItem.Item item : packet.getItems()) {
				// player leaves server
				useFakePlayerForSlot(uuidToSlotMap.get(item.getUuid()));
			}
			break;
		case UPDATE_LATENCY:
		case UPDATE_DISPLAY_NAME:
			break;
		}

		return PacketListenerResult.CANCEL;
	}

	private int findSlotForPlayer(UUID playerUUID) {
		int targetSlot = -1;
		for (int i : skinUuidToSlotMap.get(playerUUID)) {
			if (clientUuid[i] == fakePlayerUUIDs[i]) {
				targetSlot = i;
				break;
			}
		}
		if (targetSlot == -1) {
			for (int i = size - 1; i >= 0; i--) {
				if (clientUuid[i] == fakePlayerUUIDs[i]) {
					targetSlot = i;
					break;
				}
			}
		}
		if (targetSlot == -1) {
			throw new IllegalStateException("Not enough slots in tab list.");
		}
		return targetSlot;
	}

	private void useFakePlayerForSlot(int slot) {
		boolean change = clientUuid[slot] != fakePlayerUUIDs[slot];

		if (change) {
			removePlayerFromTeam(slot, clientUuid[slot], clientUsername[slot]);
			uuidToSlotMap.remove(clientUuid[slot]);
		}

		PlayerListItem packet = new PlayerListItem();
		packet.setAction(ADD_PLAYER);
		PlayerListItem.Item item = new PlayerListItem.Item();
		item.setUuid(fakePlayerUUIDs[slot]);
		item.setUsername(fakePlayerUsernames[slot]);
		item.setPing(clientPing[slot]);
		item.setDisplayName(clientText[slot]);
		item.setGamemode(0);
		item.setProperties(clientSkin[slot].toProperty());
		packet.setItems(new PlayerListItem.Item[] { item });
		sendPacket(packet);
		packet = new PlayerListItem();
		packet.setAction(UPDATE_DISPLAY_NAME);
		packet.setItems(new PlayerListItem.Item[] { item });
		sendPacket(packet);
		clientUsername[slot] = fakePlayerUsernames[slot];
		clientUuid[slot] = fakePlayerUUIDs[slot];
		uuidToSlotMap.put(clientUuid[slot], slot);

		if (change) {
			addPlayerToTeam(slot, clientUuid[slot], clientUsername[slot]);
		}
	}

	private void useRealPlayerForSlot(int slot, UUID uuid) {
		TabListItem tabListItem = serverTabList.get(uuid);

		boolean change = !clientUuid[slot].equals(uuid) || !tabListItem.getUsername().equals(clientUsername[slot]);

		if (change) {
			removePlayerFromTeam(slot, clientUuid[slot], clientUsername[slot]);
			uuidToSlotMap.remove(clientUuid[slot]);

			// if there was a fake player on that slot previously remove it from
			// the tab list
			if (clientUuid[slot] == fakePlayerUUIDs[slot]) {
				PlayerListItem packet = new PlayerListItem();
				packet.setAction(REMOVE_PLAYER);
				packet.setItems(new PlayerListItem.Item[] { item(clientUuid[slot]) });
				sendPacket(packet);
			}
		}

		PlayerListItem packet = new PlayerListItem();
		packet.setAction(ADD_PLAYER);
		PlayerListItem.Item item = new PlayerListItem.Item();
		item.setUuid(tabListItem.getUuid());
		item.setUsername(tabListItem.getUsername());
		item.setPing(clientPing[slot]);
		item.setDisplayName(clientText[slot]);
		item.setGamemode(uuid.equals(getUniqueId()) ? tabListItem.getGamemode() : 0);
		item.setProperties(tabListItem.getProperties());
		packet.setItems(new PlayerListItem.Item[] { item });
		sendPacket(packet);
		packet = new PlayerListItem();
		packet.setAction(UPDATE_DISPLAY_NAME);
		packet.setItems(new PlayerListItem.Item[] { item });
		sendPacket(packet);

		if (change) {
			clientUsername[slot] = tabListItem.getUsername();
			clientUuid[slot] = tabListItem.getUuid();
			uuidToSlotMap.put(clientUuid[slot], slot);
			addPlayerToTeam(slot, clientUuid[slot], clientUsername[slot]);
		}
	}

	private void addPlayerToTeam(int slot, UUID uuid, String player) {
		// dirty hack for citizens compatibility
		if (uuid.version() == 2)
			return;
		sendPacket(addPlayer(slot, player));
		nameToSlotMap.put(player, slot);
		if (playerToTeamMap.containsKey(player)) {
			net.md_5.bungee.api.score.Team serverTeam = serverTeams.get(playerToTeamMap.get(player));
			Team team = new Team();
			team.setMode((byte) 2);
			team.setName(fakePlayerUsernames[slot]);
			team.setDisplayName(serverTeam.getDisplayName());
			team.setPrefix(serverTeam.getPrefix());
			team.setSuffix(serverTeam.getSuffix());
			team.setFriendlyFire(serverTeam.getFriendlyFire());
			team.setNameTagVisibility(serverTeam.getNameTagVisibility());
			if (teamCollisionRuleSupported) {
				team.setCollisionRule(serverTeam.getCollisionRule());
			}
			team.setColor(serverTeam.getColor());
			sendPacket(team);
		}
	}

	private void removePlayerFromTeam(int slot, UUID uuid, String player) {
		// dirty hack for citizens compatibility
		if (uuid.version() == 2)
			return;

		if (nameToSlotMap.remove(player, slot)) {
			sendPacket(removePlayer(slot, player));
		}
		
		nameToSlotMap.remove(player, slot);
		if (playerToTeamMap.containsKey(player)) {
			Team team = new Team();
			team.setName(playerToTeamMap.get(player));
			team.setMode((byte) 3); // add player
			team.setPlayers(new String[] { player });
			sendPacket(team);
			team = new Team();
			team.setMode((byte) 2);
			team.setName(fakePlayerUsernames[slot]);
			team.setDisplayName(fakePlayerUsernames[slot]);
			team.setPrefix("");
			team.setSuffix("");
			team.setFriendlyFire((byte) 1);
			team.setNameTagVisibility("ALWAYS");
			if (teamCollisionRuleSupported) {
				team.setCollisionRule("ALWAYS");
			}
			team.setColor((byte) 0);
			sendPacket(team);
		}
	}

	@Override
	public PacketListenerResult onTeamPacket(Team packet) {
		if (fakePlayerUsernameSet.contains(packet.getName())) {
			throw new AssertionError("Team name collision: " + packet);
		}

		// update server data

		if (packet.getMode() == 1) {
			net.md_5.bungee.api.score.Team team = serverTeams.remove(packet.getName());
			if (team != null) {
				for (String player : team.getPlayers()) {
					playerToTeamMap.remove(player, team.getName());
					if (!passtrough && size != 80 && nameToSlotMap.containsKey(player)) {
						Team packet1 = new Team();
						packet1.setMode((byte) 2);
						packet1.setName(fakePlayerUsernames[nameToSlotMap.get(player)]);
						packet1.setDisplayName(packet1.getName());
						packet1.setPrefix("");
						packet1.setSuffix("");
						packet1.setFriendlyFire((byte) 1);
						packet1.setNameTagVisibility("ALWAYS");
						if (teamCollisionRuleSupported) {
							packet1.setCollisionRule("ALWAYS");
						}
						packet1.setColor((byte) 0);
						sendPacket(packet1);
					}
				}
			}

		} else {

			// Create or get old team
			net.md_5.bungee.api.score.Team t;
			if (packet.getMode() == 0) {
				t = new net.md_5.bungee.api.score.Team(packet.getName());
				serverTeams.put(packet.getName(), t);
			} else {
				t = serverTeams.get(packet.getName());
			}

			if (t != null) {
				if (packet.getMode() == 0 || packet.getMode() == 2) {
					t.setDisplayName(packet.getDisplayName());
					t.setPrefix(packet.getPrefix());
					t.setSuffix(packet.getSuffix());
					t.setFriendlyFire(packet.getFriendlyFire());
					t.setNameTagVisibility(packet.getNameTagVisibility());
					if (teamCollisionRuleSupported) {
						t.setCollisionRule(packet.getCollisionRule());
					}
					t.setColor(packet.getColor());
				}
				if (packet.getPlayers() != null) {
					for (String s : packet.getPlayers()) {
						if (packet.getMode() == 0 || packet.getMode() == 3) {
							if (playerToTeamMap.containsKey(s)) {
								serverTeams.get(playerToTeamMap.get(s)).removePlayer(s);
							}
							t.addPlayer(s);
							playerToTeamMap.put(s, t.getName());
						} else {
							t.removePlayer(s);
							playerToTeamMap.remove(s, t.getName());
						}
					}
				}
			}
		}

		if (passtrough || size == 80) {
			return PacketListenerResult.PASS;
		}

		if (packet.getMode() == 2) {
			for (String player : serverTeams.get(packet.getName()).getPlayers()) {
				if (nameToSlotMap.containsKey(player)) {
					Team team = new Team();
					team.setMode((byte) 2);
					team.setName(fakePlayerUsernames[nameToSlotMap.get(player)]);
					team.setDisplayName(packet.getDisplayName());
					team.setPrefix(packet.getPrefix());
					team.setSuffix(packet.getSuffix());
					team.setFriendlyFire(packet.getFriendlyFire());
					team.setNameTagVisibility(packet.getNameTagVisibility());
					if (teamCollisionRuleSupported) {
						team.setCollisionRule(packet.getCollisionRule());
					}
					team.setColor(packet.getColor());
					sendPacket(team);
				}
			}
		}

		boolean modified = false;

		if (packet.getMode() == 0 || packet.getMode() == 3 || packet.getMode() == 4) {
			int length = 0;
			for (String player : packet.getPlayers()) {
				if (!nameToSlotMap.containsKey(player)) {
					length++;
				} else {
					if (packet.getMode() == 4 && nameToSlotMap.containsKey(player)) {
						Team team = new Team();
						team.setMode((byte) 2);
						team.setName(fakePlayerUsernames[nameToSlotMap.get(player)]);
						team.setDisplayName(team.getName());
						team.setPrefix("");
						team.setSuffix("");
						team.setFriendlyFire((byte) 1);
						team.setNameTagVisibility("ALWAYS");
						if (teamCollisionRuleSupported) {
							team.setCollisionRule("ALWAYS");
						}
						team.setColor((byte) 0);
						sendPacket(team);
					} else if (nameToSlotMap.containsKey(player)) {
						net.md_5.bungee.api.score.Team serverTeam = serverTeams.get(playerToTeamMap.get(player));
						Team team = new Team();
						team.setMode((byte) 2);
						team.setName(fakePlayerUsernames[nameToSlotMap.get(player)]);
						team.setDisplayName(serverTeam.getDisplayName());
						team.setPrefix(serverTeam.getPrefix());
						team.setSuffix(serverTeam.getSuffix());
						team.setFriendlyFire(serverTeam.getFriendlyFire());
						team.setNameTagVisibility(serverTeam.getNameTagVisibility());
						if (teamCollisionRuleSupported) {
							team.setCollisionRule(serverTeam.getCollisionRule());
						}
						team.setColor(serverTeam.getColor());
						sendPacket(team);
					}
				}
			}
			if (length != packet.getPlayers().length) {
				String[] players = new String[length];
				length = 0;
				for (String player : packet.getPlayers()) {
					if (!nameToSlotMap.containsKey(player)) {
						players[length++] = player;
					}
				}
				packet.setPlayers(players);
				modified = true;
			}
		}

		return modified ? PacketListenerResult.MODIFIED : PacketListenerResult.PASS;
	}

	@Override
	public PacketListenerResult onPlayerListHeaderFooterPacket(PlayerListHeaderFooter packet) {
		serverHeader = packet.getHeader();
		serverFooter = packet.getFooter();

		return passtrough || clientHeader == null || clientFooter == null ? PacketListenerResult.PASS : PacketListenerResult.CANCEL;
	}

	@Override
	public void onServerSwitch() {
		serverTeams.clear();
		playerToTeamMap.clear();

		PlayerListItem packet = new PlayerListItem();
		packet.setAction(REMOVE_PLAYER);

		PlayerListItem.Item[] items = new PlayerListItem.Item[serverTabList.size()];
		Iterator<UUID> iterator = serverTabList.keySet().iterator();
		for (int i = 0; i < items.length; i++) {
			items[i] = item(iterator.next());
		}

		packet.setItems(items);
		if (onPlayerListPacket(packet) != PacketListenerResult.CANCEL) {
			sendPacket(packet);
		}

		serverTabList.clear();
		serverHeader = null;
		serverFooter = null;
	}

	@Override
	public void setPassTrough(boolean passTrough) {
		if (this.passtrough != passTrough) {
			this.passtrough = passTrough;
			if (passTrough) {
				// remove fake players
				List<PlayerListItem.Item> items = new ArrayList<>();
				for (int i = 0; i < size; i++) {
					if (clientUuid[i] == fakePlayerUUIDs[i]) {
						items.add(item(clientUuid[i]));
					}
				}
				PlayerListItem packet = new PlayerListItem();
				packet.setAction(REMOVE_PLAYER);
				packet.setItems(items.toArray(new PlayerListItem.Item[items.size()]));
				sendPacket(packet);

				if (size < 80) {
					// remove players from teams
					for (int i = 0; i < size; i++) {
						removePlayerFromTeam(i, clientUuid[i], clientUsername[i]);
					}
				}

				// restore server tab header/ footer
				if (serverHeader != null && serverFooter != null) {
					sendPacket(new PlayerListHeaderFooter(serverHeader, serverFooter));
				}

				// restore players
				items.clear();
				for (TabListItem tabListItem : serverTabList.values()) {
					if (tabListItem.getDisplayName() != null) {
						continue;
					}
					PlayerListItem.Item item = new PlayerListItem.Item();
					item.setUuid(tabListItem.getUuid());
					item.setUsername(tabListItem.getUsername());
					item.setPing(tabListItem.getPing());
					item.setProperties(tabListItem.getProperties());
					item.setGamemode(tabListItem.getGamemode());
					items.add(item);
				}
				if (!items.isEmpty()) {
					packet = new PlayerListItem();
					packet.setAction(ADD_PLAYER);
					packet.setItems(items.toArray(new PlayerListItem.Item[items.size()]));
					sendPacket(packet);
				}

				// restore player ping
				items.clear();
				for (TabListItem tabListItem : serverTabList.values()) {
					if (tabListItem.getDisplayName() == null) {
						continue;
					}
					PlayerListItem.Item item = new PlayerListItem.Item();
					item.setUuid(tabListItem.getUuid());
					item.setPing(tabListItem.getPing());
					items.add(item);
				}
				if (!items.isEmpty()) {
					packet = new PlayerListItem();
					packet.setAction(UPDATE_LATENCY);
					packet.setItems(items.toArray(new PlayerListItem.Item[items.size()]));
					sendPacket(packet);
				}

				// restore player gamemode
				items.clear();
				for (TabListItem tabListItem : serverTabList.values()) {
					if (tabListItem.getDisplayName() == null) {
						continue;
					}
					PlayerListItem.Item item = new PlayerListItem.Item();
					item.setUuid(tabListItem.getUuid());
					item.setGamemode(tabListItem.getGamemode());
					items.add(item);
				}
				if (!items.isEmpty()) {
					packet = new PlayerListItem();
					packet.setAction(UPDATE_GAMEMODE);
					packet.setItems(items.toArray(new PlayerListItem.Item[items.size()]));
					sendPacket(packet);
				}

				// restore player display name
				items.clear();
				for (TabListItem tabListItem : serverTabList.values()) {
					if (tabListItem.getDisplayName() == null) {
						continue;
					}
					if (tabListItem.getDisplayName() != null) {
						PlayerListItem.Item item = new PlayerListItem.Item();
						item.setUuid(tabListItem.getUuid());
						item.setDisplayName(tabListItem.getDisplayName());
						items.add(item);
					}
				}
				if (!items.isEmpty()) {
					packet = new PlayerListItem();
					packet.setAction(UPDATE_DISPLAY_NAME);
					packet.setItems(items.toArray(new PlayerListItem.Item[items.size()]));
					sendPacket(packet);
				}
			} else {

				// resize if necessary
				if (serverTabList.size() > size) {
					setSizeInternal(min(((serverTabList.size() + 19) / 20) * 20, 80));
				}

				if (size == 80) {
					PlayerListItem.Item[] items = new PlayerListItem.Item[size];
					for (int slot = 0; slot < size; slot++) {
						clientUuid[slot] = fakePlayerUUIDs[slot];
						clientUsername[slot] = fakePlayerUsernames[slot];
						uuidToSlotMap.put(clientUuid[slot], slot);
						PlayerListItem.Item item = new PlayerListItem.Item();
						item.setUuid(clientUuid[slot]);
						item.setUsername(clientUsername[slot]);
						item.setPing(clientPing[slot]);
						item.setDisplayName(clientText[slot]);
						item.setProperties(clientSkin[slot].toProperty());
						items[slot] = item;
					}
					PlayerListItem packet = new PlayerListItem();
					packet.setAction(ADD_PLAYER);
					packet.setItems(items);
					sendPacket(packet);
					packet = new PlayerListItem();
					packet.setAction(UPDATE_DISPLAY_NAME);
					packet.setItems(items);
					sendPacket(packet);
				} else {
					rebuildTabList();
				}

				if (clientHeader != null && clientFooter != null) {
					sendPacket(new PlayerListHeaderFooter(clientHeader, clientFooter));
				}
			}
		}
	}

	@Override
	public void setSize(int size) {

		// resize if necessary
		if (serverTabList.size() > size) {
			setSizeInternal(min(((serverTabList.size() + 19) / 20) * 20, 80));
		} else {
			setSizeInternal(size);
		}
	}

	private void setSizeInternal(int size) {
		if (size > 80 || size < 0) {
			throw new IllegalArgumentException();
		}

		if (passtrough) {
			if (size > this.size) {
				for (int slot = this.size; slot < size; slot++) {
					clientSkin[slot] = SkinManagerImpl.defaultSkin;
					clientText[slot] = "{\"text\": \"\"}";
					clientPing[slot] = 0;
				}
			}
		} else {
			if (size > this.size) {
				PlayerListItem.Item[] items = new PlayerListItem.Item[size - this.size];
				for (int slot = this.size; slot < size; slot++) {
					clientUuid[slot] = fakePlayerUUIDs[slot];
					clientUsername[slot] = fakePlayerUsernames[slot];
					clientSkin[slot] = SkinManagerImpl.defaultSkin;
					clientText[slot] = "{\"text\": \"\"}";
					clientPing[slot] = 0;
					uuidToSlotMap.put(clientUuid[slot], slot);
					PlayerListItem.Item item = new PlayerListItem.Item();
					item.setUuid(clientUuid[slot]);
					item.setUsername(clientUsername[slot]);
					item.setPing(0);
					item.setDisplayName("{\"text\": \"\"}");
					item.setProperties(new String[0][]);
					items[slot - this.size] = item;
				}
				PlayerListItem packet = new PlayerListItem();
				packet.setAction(ADD_PLAYER);
				packet.setItems(items);
				sendPacket(packet);
				packet = new PlayerListItem();
				packet.setAction(UPDATE_DISPLAY_NAME);
				packet.setItems(items);
				sendPacket(packet);

				if (size == 80) {
					int realPlayers = 0;

					for (int slot = 0; slot < this.size; slot++) {
						if (clientUuid[slot] != fakePlayerUUIDs[slot]) {
							realPlayers++;
						}
					}

					items = new PlayerListItem.Item[realPlayers];
					realPlayers = 0;
					for (int slot = 0; slot < this.size; slot++) {
						if (clientUuid[slot] != fakePlayerUUIDs[slot]) {
							PlayerListItem.Item item = new PlayerListItem.Item();
							TabListItem tabListItem = serverTabList.get(clientUuid[slot]);
							item.setUuid(tabListItem.getUuid());
							item.setGamemode(tabListItem.getGamemode());
							items[realPlayers++] = item;
							useFakePlayerForSlot(slot);
						}
						removePlayerFromTeam(slot, clientUuid[slot], clientUsername[slot]);
					}

					if (items.length != 0) {
						packet = new PlayerListItem();
						packet.setAction(UPDATE_GAMEMODE);
						packet.setItems(items);
						sendPacket(packet);
					}
				} else {
					for (int slot = this.size; slot < size; slot++) {
						addPlayerToTeam(slot, clientUuid[slot], clientUsername[slot]);
					}
					if (serverTabList.containsKey(getUniqueId()) && serverTabList.get(getUniqueId()).getGamemode() == 3
							&& clientUuid[this.size - 1].equals(getUniqueId())) {
						useFakePlayerForSlot(this.size - 1);
						useRealPlayerForSlot(size - 1, getUniqueId());
					}
				}
			} else if (size < this.size) {
				for (int slot = 0; slot < this.size; slot++) {
					if (clientUuid[slot] == fakePlayerUUIDs[slot]) {
						PlayerListItem packet = new PlayerListItem();
						packet.setAction(REMOVE_PLAYER);
						packet.setItems(new PlayerListItem.Item[] { item(clientUuid[slot]) });
						sendPacket(packet);
					}
				}
				if (this.size != 80) {
					for (int slot = 0; slot < this.size; slot++) {
						removePlayerFromTeam(slot, clientUuid[slot], clientUsername[slot]);
					}
				}
				this.size = size;
				rebuildTabList();
			}
		}
		this.size = size;
	}

	private void rebuildTabList() {
		Preconditions.checkArgument(size < 80 && size >= 0, "Wrong size: " + size);
		Set<UUID> realPlayers = new HashSet<>(serverTabList.keySet());
		boolean isSpectator = serverTabList.containsKey(getUniqueId()) && serverTabList.get(getUniqueId()).getGamemode() == 3;
		if (isSpectator) {
			realPlayers.remove(getUniqueId());
		}

		PlayerListItem.Item[] items = new PlayerListItem.Item[size];
		for (int i = 0; i < size; i++) {
			PlayerListItem.Item item = new PlayerListItem.Item();

			UUID skinOwner = clientSkin[i].getOwner();
			if (skinOwner != null && realPlayers.contains(skinOwner)) {
				// use real player
				TabListItem tabListItem = serverTabList.get(skinOwner);
				item.setUuid(tabListItem.getUuid());
				item.setUsername(tabListItem.getUsername());
				item.setProperties(tabListItem.getProperties());
				realPlayers.remove(item.getUuid());
			} else if (size - i - (isSpectator ? 1 : 0) > realPlayers.size()) {
				item.setUuid(fakePlayerUUIDs[i]);
				item.setUsername(fakePlayerUsernames[i]);
				item.setProperties(clientSkin[i].toProperty());
			} else if (!realPlayers.isEmpty()) {
				UUID uuid = realPlayers.iterator().next();
				realPlayers.remove(uuid);
				TabListItem tabListItem = serverTabList.get(uuid);
				item.setUuid(tabListItem.getUuid());
				item.setUsername(tabListItem.getUsername());
				item.setProperties(tabListItem.getProperties());
			} else {
				TabListItem tabListItem = serverTabList.get(getUniqueId());
				item.setUuid(tabListItem.getUuid());
				item.setUsername(tabListItem.getUsername());
				item.setProperties(tabListItem.getProperties());
				item.setGamemode(tabListItem.getGamemode());
			}
			item.setPing(clientPing[i]);
			item.setDisplayName(clientText[i]);

			clientUuid[i] = item.getUuid();
			clientUsername[i] = item.getUsername();
			uuidToSlotMap.put(item.getUuid(), i);

			addPlayerToTeam(i, item.getUuid(), item.getUsername());

			items[i] = item;
		}
		PlayerListItem packet = new PlayerListItem();
		packet.setAction(ADD_PLAYER);
		packet.setItems(items);
		sendPacket(packet);
		packet = new PlayerListItem();
		packet.setAction(UPDATE_DISPLAY_NAME);
		packet.setItems(items);
		sendPacket(packet);
	}

	@Override
	public void setSlot(int index, Skin skin0, String text, int ping) {
		Preconditions.checkElementIndex(index, size);

		PlayerSkin skin = skin0 instanceof PlayerSkin ? (PlayerSkin) skin0 : new PlayerSkin(skin0.getOwner(), skin0.toProperty());

		if (!passtrough) {
			if (clientSkin[index].equals(skin)) {
				updatePingInternal(index, ping);
			} else {
				if (clientSkin[index].getOwner() != null) {
					skinUuidToSlotMap.remove(clientSkin[index].getOwner(), index);
				}
				if (skin.getOwner() != null) {
					skinUuidToSlotMap.put(skin.getOwner(), index);
				}
				boolean updated = false;
				if (skin.getOwner() != null) {
					if (size < 80 && !clientUuid[index].equals(skin.getOwner()) && serverTabList.containsKey(skin.getOwner())
							&& (!clientUuid[index].equals(getUniqueId()) || serverTabList.get(getUniqueId()).getGamemode() != 3)
							&& (!skin.getOwner().equals(getUniqueId()) || serverTabList.get(getUniqueId()).getGamemode() != 3)
							&& (clientSkin[uuidToSlotMap.get(skin.getOwner())].getOwner() == null
									|| !clientSkin[uuidToSlotMap.get(skin.getOwner())].getOwner().equals(skin.getOwner()))) {
						clientSkin[index] = skin;
						clientPing[index] = ping;
						int slot = uuidToSlotMap.get(skin.getOwner());
						if (clientUuid[index] == fakePlayerUUIDs[index]) {
							useFakePlayerForSlot(slot);
							useRealPlayerForSlot(index, skin.getOwner());
						} else {
							useRealPlayerForSlot(slot, clientUuid[index]);
							useRealPlayerForSlot(index, skin.getOwner());
						}
						updated = true;
					}
				}
				if (!updated) {
					if (clientUuid[index] == fakePlayerUUIDs[index]) {
						PlayerListItem packet = new PlayerListItem();
						PlayerListItem.Item item = new PlayerListItem.Item();
						item.setUuid(clientUuid[index]);
						item.setUsername(clientUsername[index]);
						item.setPing(ping);
						item.setDisplayName(text);
						item.setProperties(skin.toProperty());
						packet.setAction(ADD_PLAYER);
						packet.setItems(new PlayerListItem.Item[] { item });
						sendPacket(packet);
						clientText[index] = "";
					} else {
						updatePingInternal(index, ping);
					}
				}
			}
			updateTextInternal(index, text);
		}
		clientSkin[index] = skin;
		clientText[index] = text;
		clientPing[index] = ping;
	}

	@Override
	public void updateText(int index, String text) {
		updateTextInternal(index, text);
	}

	private void updateTextInternal(int index, String text) {
		Preconditions.checkElementIndex(index, size);

		if (!passtrough && !clientText[index].equals(text)) {
			PlayerListItem packet = new PlayerListItem();
			PlayerListItem.Item item = new PlayerListItem.Item();
			item.setUuid(clientUuid[index]);
			item.setDisplayName(text);
			packet.setAction(UPDATE_DISPLAY_NAME);
			packet.setItems(new PlayerListItem.Item[] { item });
			sendPacket(packet);
		}
		clientText[index] = text;
	}

	@Override
	public void updatePing(int index, int ping) {
		updatePingInternal(index, ping);
	}

	private void updatePingInternal(int index, int ping) {
		Preconditions.checkElementIndex(index, size);

		if (!passtrough && clientPing[index] != ping) {
			PlayerListItem packet = new PlayerListItem();
			PlayerListItem.Item item = new PlayerListItem.Item();
			item.setUuid(clientUuid[index]);
			item.setPing(ping);
			packet.setAction(UPDATE_LATENCY);
			packet.setItems(new PlayerListItem.Item[] { item });
			sendPacket(packet);
		}
		clientPing[index] = ping;
	}

	@Override
	public void setHeaderFooter(String header, String footer) {
		sendPacket(new PlayerListHeaderFooter(header, footer));
		clientHeader = header;
		clientFooter = footer;
	}

	private static PlayerListItem.Item item(UUID uuid) {
		PlayerListItem.Item item1 = new PlayerListItem.Item();
		item1.setUuid(uuid);
		return item1;
	}

	private static Team removePlayer(int slot, String player) {
		Team packet = new Team(fakePlayerUsernames[slot]);
		packet.setMode((byte) 4);
		packet.setPlayers(new String[] { player });
		return packet;
	}

	private static Team addPlayer(int slot, String player) {
		Team packet = new Team(fakePlayerUsernames[slot]);
		packet.setMode((byte) 3);
		packet.setPlayers(new String[] { player });
		return packet;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	static class TabListItem {
		private UUID uuid;
		private String[][] properties;
		private String username;
		private String displayName;
		private int ping;
		private int gamemode;

		private TabListItem(PlayerListItem.Item item) {
			this(item.getUuid(), item.getProperties(), item.getUsername(), item.getDisplayName(), item.getPing(), item.getGamemode());
		}
	}
}
