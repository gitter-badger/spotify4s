package com.github.gondolav.spotify4s

import java.net.URI

import com.github.gondolav.spotify4s.auth._
import com.github.gondolav.spotify4s.entities._
import requests.RequestFailedException
import upickle.default._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class Spotify(authFlow: AuthFlow) {

  private val endpoint = "https://api.spotify.com/v1"

  var authObj: AuthObj = authFlow.authenticate match {
    case Left(error) => throw new AuthException(f"An error occurred while authenticating: '${error.errorDescription}'\n", error)
    case Right(value) => value
  }

  def this(clientID: String, clientSecret: String) = this(ClientCredentials(clientID, clientSecret))

  def this(clientID: String, clientSecret: String, redirectURI: URI, scopes: List[String] = Nil, withPKCE: Boolean = false) =
    this(if (withPKCE) AuthCodeWithPKCE(clientID, clientSecret, redirectURI, scopes) else AuthCode(clientID, clientSecret, redirectURI, scopes))

  /**
   * Requests refreshed tokens, updating the field [[authObj]]. No additional action is required by the user.
   *
   * Access tokens are deliberately set to expire after a short time, after which new tokens may be granted by
   * supplying the refresh token originally obtained during the authorization code exchange.
   */
  def requestRefreshedToken(): Unit = authFlow.requestRefreshedToken(authObj.refreshToken) match {
    case Left(error) => throw new AuthException(f"An error occurred while refreshing the token: '${error.errorDescription}'\n", error)
    case Right(value) => authObj = value
  }

  /**
   * Gets Spotify catalog information for a single album.
   *
   * @param id     the Spotify ID for the album
   * @param market (optional) an ISO 3166-1 alpha-2 country code or the string from_token. Provide this parameter if you want to apply Track Relinking
   * @return an [[Album]] on success, otherwise it returns [[Error]]
   */
  def getAlbum(id: String, market: String = ""): Either[Error, Album] = withErrorHandling {
    val req = requests.get(f"$endpoint/albums/$id", headers = List(("Authorization", f"Bearer ${authObj.accessToken}")), params = if (market.nonEmpty) List(("market", market)) else Nil)
    Right(Album.fromJson(read[AlbumJson](req.text)))
  }

  /**
   * Gets Spotify catalog information about an album’s tracks. Optional parameters can be used to limit the number of tracks returned.
   *
   * @param id     the Spotify ID for the album
   * @param limit  (optional) the maximum number of tracks to return. Default: 20. Minimum: 1. Maximum: 50
   * @param offset (optional) the index of the first track to return. Default: 0 (the first object). Use with limit to get the next set of tracks
   * @param market (optional) an ISO 3166-1 alpha-2 country code or the string from_token. Provide this parameter if you want to apply Track Relinking
   * @return a [[Paging]] object wrapping [[Track]]s on success, otherwise it returns [[Error]]
   */
  def getAlbumTracks(id: String, limit: Int = 20, offset: Int = 0, market: String = ""): Either[Error, Paging[Track]] = withErrorHandling {
    require(1 <= limit && limit <= 50, "The limit parameter must be between 1 and 50")
    require(offset >= 0, "The offset parameter must be non-negative")

    val req = requests.get(f"$endpoint/albums/$id/tracks",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("limit", limit.toString), ("offset", offset.toString)) ++ (if (market.nonEmpty) List(("market", market)) else Nil))

    val res = read[Paging[TrackJson]](req.text)
    Right(res.copy(items = res.items.map(_.map(Track.fromJson))))
  }

  /**
   * Gets Spotify catalog information for multiple albums identified by their Spotify IDs.
   *
   * Objects are returned in the order requested. If an object is not found, a null value is returned in the
   * appropriate position. Duplicate ids in the query will result in duplicate objects in the response.
   *
   * @param ids    a list containing the Spotify IDs for the albums. Maximum: 20 IDs
   * @param market (optional) an ISO 3166-1 alpha-2 country code or the string from_token. Provide this parameter if you want to apply Track Relinking
   * @return a List of [[Album]]s on success, otherwise it returns [[Error]]
   */
  def getAlbums(ids: List[String], market: String = ""): Either[Error, List[Album]] = withErrorHandling {
    require(ids.length <= 20, "The maximum number of IDs is 20")

    val req = requests.get(f"$endpoint/albums",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("ids", ids.mkString(","))) ++ (if (market.nonEmpty) List(("market", market)) else Nil))

    val res = read[Map[String, List[AlbumJson]]](req.text)
    Right(res("albums").map(Album.fromJson))
  }

  /**
   * Gets a single category used to tag items in Spotify (on, for example, the Spotify player’s “Browse” tab).
   *
   * @param id      the Spotify category ID for the category
   * @param country (optional) a country: an ISO 3166-1 alpha-2 country code. Provide this parameter to ensure that the category exists for a particular country
   * @param locale  (optional) the desired language, consisting of an ISO 639-1 language code and an ISO 3166-1 alpha-2
   *                country code, joined by an underscore. For example: es_MX, meaning "Spanish (Mexico)".
   *                Provide this parameter if you want the category strings returned in a particular language.
   *                Note that, if locale is not supplied, or if the specified language is not available,
   *                the category strings returned will be in the Spotify default language (American English)
   * @return a [[Category]] on success, otherwise it returns [[Error]]
   */
  def getCategory(id: String, country: String = "", locale: String = ""): Either[Error, Category] = withErrorHandling {
    val req = requests.get(f"$endpoint/browse/categories/$id",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = (if (country.nonEmpty) List(("country", country)) else Nil) ++ (if (locale.nonEmpty) List(("locale", locale)) else Nil))

    Right(read[Category](req.text))
  }

  /**
   * Gets a list of Spotify playlists tagged with a particular category.
   *
   * @param id      the Spotify category ID for the category
   * @param country (optional) a country: an ISO 3166-1 alpha-2 country code
   * @param limit   (optional) the maximum number of items to return. Default: 20. Minimum: 1. Maximum: 50
   * @param offset  (optional) the index of the first item to return. Default: 0 (the first object).
   *                Use with limit to get the next set of items
   * @return a [[Paging]] object wrapping [[Playlist]]s on success, otherwise it returns [[Error]]
   */
  def getCategoryPlaylists(id: String, country: String = "", limit: Int = 20, offset: Int = 0): Either[Error, Paging[Playlist]] = withErrorHandling {
    require(1 <= limit && limit <= 50, "The limit parameter must be between 1 and 50")
    require(offset >= 0, "The offset parameter must be non-negative")

    val req = requests.get(f"$endpoint/browse/categories/$id/playlists",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("limit", limit.toString), ("offset", offset.toString)) ++ (if (country.nonEmpty) List(("country", country)) else Nil))

    val res = {
      val map = read[Map[String, Paging[PlaylistJson]]](req.text)
      map("playlists")
    }

    Right(res.copy(items = res.items.map(_.map(Playlist.fromJson))))
  }

  /**
   * Gets a list of categories used to tag items in Spotify (on, for example, the Spotify player’s “Browse” tab).
   *
   * @param country (optional) a country: an ISO 3166-1 alpha-2 country code
   *                Provide this parameter if you want to narrow the list of returned categories to those relevant to
   *                a particular country. If omitted, the returned items will be globally relevant
   * @param locale  (optional) the desired language, consisting of an ISO 639-1 language code and an ISO 3166-1 alpha-2
   *                country code, joined by an underscore. For example: es_MX, meaning “Spanish (Mexico)”.
   *                Provide this parameter if you want the category metadata returned in a particular language.
   *                Note that, if locale is not supplied, or if the specified language is not available, all
   *                strings will be returned in the Spotify default language (American English). The locale
   *                parameter, combined with the country parameter, may give odd results if not carefully matched.
   *                For example country=SE&locale=de_DE will return a list of categories relevant to Sweden but as
   *                German language strings
   * @param limit   (optional) the maximum number of categories to return. Default: 20. Minimum: 1. Maximum: 50
   * @param offset  (optional) the index of the first item to return. Default: 0 (the first object).
   *                Use with limit to get the next set of categories.
   * @return a [[Paging]] object wrapping [[Category]]s on success, otherwise it returns [[Error]]
   */
  def getCategories(country: String = "", locale: String = "", limit: Int = 20, offset: Int = 0): Either[Error, Paging[Category]] = withErrorHandling {
    require(1 <= limit && limit <= 50, "The limit parameter must be between 1 and 50")
    require(offset >= 0, "The offset parameter must be non-negative")

    val req = requests.get(f"$endpoint/browse/categories",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("limit", limit.toString), ("offset", offset.toString))
        ++ (if (country.nonEmpty) List(("country", country)) else Nil)
        ++ (if (locale.nonEmpty) List(("locale", locale)) else Nil))

    val res = {
      val map = read[Map[String, Paging[Category]]](req.text)
      map("categories")
    }

    Right(res)
  }

  /**
   * Gets a list of Spotify featured playlists (shown, for example, on a Spotify player’s ‘Browse’ tab).
   *
   * @param locale    (optional) the desired language, consisting of a lowercase ISO 639-1 language code and an uppercase
   *                  ISO 3166-1 alpha-2 country code, joined by an underscore. For example: es_MX,
   *                  meaning “Spanish (Mexico)”. Provide this parameter if you want the results returned in a particular
   *                  language (where available). Note that, if locale is not supplied, or if the specified language
   *                  is not available, all strings will be returned in the Spotify default language (American English).
   *                  The locale parameter, combined with the country parameter, may give odd results if not carefully
   *                  matched. For example country=SE&locale=de_DE will return a list of categories relevant to Sweden
   *                  but as German language strings
   * @param country   (optional) a country: an ISO 3166-1 alpha-2 country code. Provide this parameter if you want the
   *                  list of returned items to be relevant to a particular country. If omitted, the returned items
   *                  will be relevant to all countries
   * @param timestamp (optional) a timestamp in ISO 8601 format: yyyy-MM-ddTHH:mm:ss. Use this parameter to specify
   *                  the user’s local time to get results tailored for that specific date and time in the day.
   *                  If not provided, the response defaults to the current UTC time. Example: “2014-10-23T09:00:00”
   *                  for a user whose local time is 9AM. If there were no featured playlists (or there is no data)
   *                  at the specified time, the response will revert to the current UTC time
   * @param limit     (optional) the maximum number of items to return. Default: 20. Minimum: 1. Maximum: 50
   * @param offset    (optional) the index of the first item to return. Default: 0 (the first object).
   *                  Use with limit to get the next set of items
   * @return a pair containing a message and a [[Paging]] object wrapping [[Playlist]]s on success, otherwise it returns [[Error]]
   */
  def getFeaturedPlaylists(locale: String = "", country: String = "", timestamp: String = "", limit: Int = 20, offset: Int = 0): Either[Error, (String, Paging[Playlist])] = withErrorHandling {
    require(1 <= limit && limit <= 50, "The limit parameter must be between 1 and 50")
    require(offset >= 0, "The offset parameter must be non-negative")

    val req = requests.get(f"$endpoint/browse/featured-playlists",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("limit", limit.toString), ("offset", offset.toString))
        ++ (if (country.nonEmpty) List(("country", country)) else Nil)
        ++ (if (locale.nonEmpty) List(("locale", locale)) else Nil)
        ++ (if (timestamp.nonEmpty) List(("timestamp", timestamp)) else Nil))

    val res = read[FeaturedPlaylistsAnswer](req.text)

    Right((res.message, res.playlists.copy(items = res.playlists.items.map(_.map(Playlist.fromJson)))))
  }

  /**
   * Gets a list of new album releases featured in Spotify (shown, for example, on a Spotify player’s “Browse” tab).
   *
   * @param country (optional) a country: an ISO 3166-1 alpha-2 country code. Provide this parameter if you want the
   *                list of returned items to be relevant to a particular country. If omitted, the returned items
   *                will be relevant to all countries
   * @param limit   (optional) the maximum number of items to return. Default: 20. Minimum: 1. Maximum: 50
   * @param offset  (optional) the index of the first item to return. Default: 0 (the first object).
   *                Use with limit to get the next set of items
   * @return a [[Paging]] object wrapping [[Album]]s on success, otherwise it returns [[Error]]
   */
  def getNewReleases(country: String = "", limit: Int = 20, offset: Int = 0): Either[Error, Paging[Album]] = withErrorHandling {
    require(1 <= limit && limit <= 50, "The limit parameter must be between 1 and 50")
    require(offset >= 0, "The offset parameter must be non-negative")

    val req = requests.get(f"$endpoint/browse/new-releases",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("limit", limit.toString), ("offset", offset.toString))
        ++ (if (country.nonEmpty) List(("country", country)) else Nil))

    val res = {
      val map = read[Map[String, Paging[AlbumJson]]](req.text)
      map("albums")
    }

    Right(res.copy(items = res.items.map(_.map(Album.fromJson))))
  }

  /**
   * Creates a playlist-style listening experience based on seed artists, tracks and genres. Recommendations are
   * generated based on the available information for a given seed entity and matched against similar artists and
   * tracks. If there is sufficient information about the provided seeds, a list of tracks will be returned together
   * with pool size details. For artists and tracks that are very new or obscure there might not be enough data to
   * generate a list of tracks.
   *
   * N.B. at least one seed must be provided.
   *
   * @param limit       (optional) the target size of the list of recommended tracks. For seeds with unusually small
   *                    pools or when highly restrictive filtering is applied, it may be impossible to generate the
   *                    requested number of recommended tracks. Debugging information for such cases is available in
   *                    the response. Default: 20. Minimum: 1. Maximum: 100
   * @param market      (optional) an ISO 3166-1 alpha-2 country code or the string from_token. Provide this parameter
   *                    if you want to apply Track Relinking. Because min_*, max_* and target_* are applied to pools
   *                    before relinking, the generated results may not precisely match the filters applied. Original,
   *                    non-relinked tracks are available via the linked_from attribute of the relinked track response
   * @param attributes  (optional) a map containing tunable track attributes. Keys can start by either "max_", "min_"
   *                    or "target_". See [[https://developer.spotify.com/documentation/web-api/reference/browse/get-recommendations/ the Spotify documentation]] for more details.
   * @param seedArtists a comma separated list of Spotify IDs for seed artists. Up to 5 seed values may be provided in
   *                    any combination of seed_artists, seed_tracks and seed_genres
   * @param seedGenres  a comma separated list of any genres in the set of available genre seeds. Up to 5 seed values
   *                    may be provided in any combination of seed_artists, seed_tracks and seed_genres
   * @param seedTracks  a comma separated list of Spotify IDs for a seed track. Up to 5 seed values may be provided in
   *                    any combination of seed_artists, seed_tracks and seed_genres
   * @return a [[Recommendations]] on success, otherwise it returns [[Error]]
   */
  def getRecommendations(limit: Int = 20, market: String = "", attributes: Map[String, String] = Map.empty,
                         seedArtists: List[String] = Nil, seedGenres: List[String] = Nil,
                         seedTracks: List[String] = Nil): Either[Error, Recommendations] = withErrorHandling {
    require(1 <= limit && limit <= 100, "The limit parameter must be between 1 and 100")
    require(!(seedArtists.isEmpty || seedGenres.isEmpty || seedTracks.isEmpty), "At least one seed must be provided")

    val req = requests.get(f"$endpoint/recommendations",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = attributes.toList.map { case (name, value) => (name, value) }
        ++ List(("limit", limit.toString))
        ++ (if (seedArtists.nonEmpty) List(("seed_artists", seedArtists.mkString(","))) else Nil)
        ++ (if (seedGenres.nonEmpty) List(("seed_genres", seedGenres.mkString(","))) else Nil)
        ++ (if (seedTracks.nonEmpty) List(("seed_tracks", seedTracks.mkString(","))) else Nil)
        ++ (if (market.nonEmpty) List(("market", market)) else Nil))

    Right(Recommendations.fromJson(read[RecommendationsJson](req.text)))
  }

  /**
   * Gets Spotify catalog information for a single artist identified by their unique Spotify ID.
   *
   * @param id the Spotify ID for the artist
   * @return an [[Artist]] on success, otherwise it returns [[Error]]
   */
  def getArtist(id: String): Either[Error, Artist] = withErrorHandling {
    val req = requests.get(f"$endpoint/artists/$id", headers = List(("Authorization", f"Bearer ${authObj.accessToken}")))
    Right(Artist.fromJson(read[ArtistJson](req.text)))
  }

  /**
   * Gets Spotify catalog information about an artist’s albums. Optional parameters can be specified in the query string to filter and sort the response.
   *
   * @param id                        the Spotify ID for the artist
   * @param includeGroups             (optional) a list of keywords that will be used to filter the response. If not supplied, all album types will be returned. Valid values are:
   *                                - album
   *                                - single
   *                                - appears_on
   *                                - compilation
   *
   *                                  For example: include_groups=album,single
   * @param market                    (optional) an ISO 3166-1 alpha-2 country code or the string from_token.
   *                                  Supply this parameter to limit the response to one particular geographical market. For example, for albums available in Sweden: country=SE.
   *
   *                                  If not given, results will be returned for all countries and you are likely to get duplicate results per album, one for each country in which the album is available!
   * @param limit                     (optional) the number of album objects to return. Default: 20. Minimum: 1. Maximum: 50. For example: limit=2
   * @param offset                    (optional) the index of the first album to return. Default: 0 (i.e., the first album). Use with limit to get the next set of albums.
   * @return a [[Paging]] object wrapping [[Album]]s on success, otherwise it returns [[Error]]
   */
  def getArtistAlbums(id: String, includeGroups: List[String] = Nil, market: String = "", limit: Int = 20, offset: Int = 0): Either[Error, Paging[Album]] = withErrorHandling {
    require(1 <= limit && limit <= 50, "The limit parameter must be between 1 and 50")
    require(offset >= 0, "The offset parameter must be non-negative")
    require(!(includeGroups.nonEmpty && (includeGroups.toSet -- Set("album", "single", "appears_on", "compilation")).nonEmpty), "Valid values for the includeGroups parameter are album, single, appears_on and compilation")

    val req = requests.get(f"$endpoint/artists/$id/albums",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("limit", limit.toString), ("offset", offset.toString)) ++
        (if (market.nonEmpty) List(("market", market)) else Nil) ++
        (if (includeGroups.nonEmpty) List(("include_groups", includeGroups.mkString(","))) else Nil))

    val res = read[Paging[AlbumJson]](req.text)
    Right(res.copy(items = res.items.map(_.map(Album.fromJson))))
  }

  /**
   * Gets Spotify catalog information about an artist’s top tracks by country.
   *
   * @param id      the Spotify ID for the artist
   * @param country an ISO 3166-1 alpha-2 country code or the string from_token.
   * @return a List of up to 10 [[Track]]s on success, otherwise it returns [[Error]]
   */
  def getArtistTopTracks(id: String, country: String): Either[Error, List[Track]] = withErrorHandling {
    val req = requests.get(f"$endpoint/artists/$id/top-tracks",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("country", country)))

    val res = read[Map[String, List[TrackJson]]](req.text)
    Right(res("tracks").map(Track.fromJson))
  }

  /**
   * Gets Spotify catalog information about artists similar to a given artist. Similarity is based on analysis of the Spotify community’s listening history.
   *
   * @param id the Spotify ID for the artist
   * @return a List of up to 20 [[Artist]]s on success, otherwise it returns [[Error]]
   */
  def getArtistRelatedArtists(id: String): Either[Error, List[Artist]] = withErrorHandling {
    val req = requests.get(f"$endpoint/artists/$id/related-artists",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")))

    val res = read[Map[String, List[ArtistJson]]](req.text)
    Right(res("artists").map(Artist.fromJson))
  }

  /**
   * Gets Spotify catalog information for several artists based on their Spotify IDs.
   *
   * Objects are returned in the order requested. If an object is not found, a null value is returned in the
   * appropriate position. Duplicate ids in the query will result in duplicate objects in the response.
   *
   * @param ids a list containing the Spotify IDs for the albums. Maximum: 50 IDs
   * @return a List of [[Artist]]s on success, otherwise it returns [[Error]]
   */
  def getArtists(ids: List[String]): Either[Error, List[Artist]] = withErrorHandling {
    require(ids.length <= 50, "The maximum number of IDs is 50")

    val req = requests.get(f"$endpoint/artists",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("ids", ids.mkString(","))))

    val res = read[Map[String, List[ArtistJson]]](req.text)
    Right(res("artists").map(Artist.fromJson))
  }

  /**
   * Gets Spotify catalog information for a single episode identified by its unique Spotify ID.
   *
   * Reading the user’s resume points on episode objects requires the user-read-playback-position scope.
   *
   * If an episode is unavailable in the given market the HTTP status code in the response header is 404 NOT FOUND.
   *
   * @param id     the Spotify ID for the episode
   * @param market (optional) an ISO 3166-1 alpha-2 country code.
   *               If a country code is specified, only shows and episodes that are available in that market
   *               will be returned. If a valid user access token is specified in the request header, the country
   *               associated with the user account will take priority over this parameter. Note: If neither market or
   *               user country are provided, the content is considered unavailable for the client.
   *               Users can view the country that is associated with their account in the account settings
   * @return an [[Episode]] on success, otherwise it returns [[Error]]
   */
  def getEpisode(id: String, market: String = ""): Either[Error, Episode] = withErrorHandling {
    val req = requests.get(f"$endpoint/episodes/$id",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = if (market.nonEmpty) List(("market", market)) else Nil)

    val res = read[EpisodeJson](req.text)
    Right(Episode.fromJson(res))
  }

  /**
   * Gets Spotify catalog information for multiple episodes based on their Spotify IDs.
   *
   * Objects are returned in the order requested. If an object is not found or unavailable in the given market, a
   * null value is returned in the appropriate position.
   *
   * @param ids    a comma-separated list of the Spotify IDs for the episodes. Maximum: 50 IDs
   * @param market (optional) an ISO 3166-1 alpha-2 country code. If a country code is specified, only shows and
   *               episodes that are available in that market will be returned. If a valid user access token is
   *               specified in the request header, the country associated with the user account will take priority
   *               over this parameter. Note: If neither market or user country are provided, the content is
   *               considered unavailable for the client. Users can view the country that is associated with
   *               their account in the account settings
   * @return a List of [[Episode]]s on success, otherwise it returns [[Error]]
   */
  def getEpisodes(ids: List[String], market: String = ""): Either[Error, List[Episode]] = withErrorHandling {
    require(ids.length <= 50, "The maximum number of IDs is 50")

    val req = requests.get(f"$endpoint/episodes",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("ids", ids.mkString(","))))

    val res = read[Map[String, List[EpisodeJson]]](req.text)
    Right(res("episodes").map(Episode.fromJson))
  }

  /**
   * Gets Spotify catalog information for a single show identified by its unique Spotify ID.
   *
   * Reading the user’s resume points on episode objects requires the user-read-playback-position scope.
   *
   * If a show is unavailable in the given market the HTTP status code in the response header is 404 NOT FOUND.
   * Unavailable episodes are filtered out.
   *
   * @param id     the Spotify ID for the show.
   * @param market (optional) an ISO 3166-1 alpha-2 country code. If a country code is specified, only shows and
   *               episodes that are available in that market will be returned. If a valid user access token is
   *               specified in the request header, the country associated with the user account will take priority
   *               over this parameter. Note: If neither market or user country are provided, the content is
   *               considered unavailable for the client. Users can view the country that is associated with their
   *               account in the account settings
   * @return a [[Show]] on success, otherwise it returns [[Error]]
   */
  def getShow(id: String, market: String = ""): Either[Error, Show] = withErrorHandling {
    val req = requests.get(f"$endpoint/shows/$id",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = if (market.nonEmpty) List(("market", market)) else Nil)

    val res = read[ShowJson](req.text)
    Right(Show.fromJson(res))
  }

  /**
   * Gets Spotify catalog information for multiple shows based on their Spotify IDs.
   *
   * Reading the user’s resume points on episode objects requires the user-read-playback-position scope.
   *
   * Objects are returned in the order requested. If an object is not found or unavailable in the given market, a null
   * value is returned in the appropriate position.
   *
   * @param ids    a comma-separated list of the Spotify IDs for the shows. Maximum: 50 IDs
   * @param market (optional) an ISO 3166-1 alpha-2 country code. If a country code is specified, only shows and
   *               episodes that are available in that market will be returned. If a valid user access token is
   *               specified in the request header, the country associated with the user account will take priority
   *               over this parameter. Note: If neither market or user country are provided, the content is considered
   *               unavailable for the client. Users can view the country that is associated with their account in the account settings.
   * @return a List of [[Show]]s on success, otherwise it returns [[Error]]
   */
  def getShows(ids: List[String], market: String = ""): Either[Error, List[Show]] = withErrorHandling {
    require(ids.length <= 50, "The maximum number of IDs is 50")

    val req = requests.get(f"$endpoint/shows",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("ids", ids.mkString(","))) ++ (if (market.nonEmpty) List(("market", market)) else Nil))

    val res = read[Map[String, List[ShowJson]]](req.text)
    Right(res("shows").map(Show.fromJson))
  }

  /**
   * Gets Spotify catalog information about a show’s episodes. Optional parameters can be used to limit the number of
   * episodes returned.
   *
   * Reading the user’s resume points on episode objects requires the user-read-playback-position scope.
   *
   * If a show is unavailable in the given market the HTTP status code in the response header is 404 NOT FOUND.
   * Unavailable episodes are filtered out.
   *
   * @param id     the Spotify ID for the show
   * @param market (optional) an ISO 3166-1 alpha-2 country code. If a country code is specified, only shows and
   *               episodes that are available in that market will be returned. If a valid user access token is
   *               specified in the request header, the country associated with the user account will take priority
   *               over this parameter. Note: If neither market or user country are provided, the content is
   *               considered unavailable for the client. Users can view the country that is associated with their
   *               account in the account settings
   * @param limit  (optional) the maximum number of episodes to return. Default: 20. Minimum: 1. Maximum: 50
   * @param offset (optional) the index of the first episode to return. Default: 0 (the first object). Use with limit
   *               to get the next set of episodes
   * @return a [[Paging]] object wrapping [[Episode]]s on success, otherwise it returns [[Error]]
   */
  def getShowEpisodes(id: String, market: String = "", limit: Int = 20, offset: Int = 0): Either[Error, Paging[Episode]] = withErrorHandling {
    require(1 <= limit && limit <= 50, "The limit parameter must be between 1 and 50")
    require(offset >= 0, "The offset parameter must be non-negative")

    val req = requests.get(f"$endpoint/shows/$id/episodes",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("limit", limit.toString), ("offset", offset.toString)) ++
        (if (market.nonEmpty) List(("market", market)) else Nil))

    val res = read[Paging[EpisodeJson]](req.text)
    Right(res.copy(items = res.items.map(_.map(Episode.fromJson))))
  }

  /**
   * Gets a detailed audio analysis for a single track identified by its unique Spotify ID.
   *
   * The Audio Analysis endpoint provides low-level audio analysis for all of the tracks in the Spotify catalog.
   * The Audio Analysis describes the track’s structure and musical content, including rhythm, pitch, and timbre.
   * All information is precise to the audio sample.
   *
   * Many elements of analysis include confidence values, a floating-point number ranging from 0.0 to 1.0.
   * Confidence indicates the reliability of its corresponding attribute. Elements carrying a small confidence value
   * should be considered speculative. There may not be sufficient data in the audio to compute the attribute with
   * high certainty.
   *
   * @param id the Spotify ID for the track
   * @return an [[AudioAnalysis]] on success, otherwise it returns [[Error]]
   */
  def getTrackAudioAnalysis(id: String): Either[Error, AudioAnalysis] = withErrorHandling {
    val req = requests.get(f"$endpoint/audio-analysis/$id",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")))

    val res = read[AudioAnalysisJson](req.text)
    Right(AudioAnalysis.fromJson(res))
  }

  /**
   * Gets audio feature information for a single track identified by its unique Spotify ID.
   *
   * @param id the Spotify ID for the track
   * @return an [[AudioFeatures]] on success, otherwise it returns [[Error]]
   */
  def getTrackAudioFeatures(id: String): Either[Error, AudioFeatures] = withErrorHandling {
    val req = requests.get(f"$endpoint/audio-features/$id",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")))

    val res = read[AudioFeaturesJson](req.text)
    Right(AudioFeatures.fromJson(res))
  }

  /**
   * Gets audio features for multiple tracks based on their Spotify IDs.
   *
   * Objects are returned in the order requested. If an object is not found, a null value is returned in the
   * appropriate position. Duplicate ids in the query will result in duplicate objects in the response.
   *
   * @param ids a comma-separated list of the Spotify IDs for the tracks. Maximum: 100 IDs
   * @return a List of [[AudioFeatures]] on success, otherwise it returns [[Error]]
   */
  def getTracksAudioFeatures(ids: List[String]): Either[Error, List[AudioFeatures]] = withErrorHandling {
    require(ids.length <= 100, "The maximum number of IDs is 100")

    val req = requests.get(f"$endpoint/audio-features",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("ids", ids.mkString(","))))

    val res = read[Map[String, List[AudioFeaturesJson]]](req.text)
    Right(res("audio_features").map(AudioFeatures.fromJson))
  }

  /**
   * Gets Spotify catalog information for multiple tracks based on their Spotify IDs.
   *
   * Objects are returned in the order requested. If an object is not found, a null value is returned in the
   * appropriate position. Duplicate ids in the query will result in duplicate objects in the response.
   *
   * @param ids    a comma-separated list of the Spotify IDs for the tracks. Maximum: 50 IDs
   * @param market (optional) an ISO 3166-1 alpha-2 country code or the string from_token. Provide this parameter if
   *               you want to apply Track Relinking
   * @return a List of [[Track]] on success, otherwise it returns [[Error]]
   */
  def getTracks(ids: List[String], market: String = ""): Either[Error, List[Track]] = withErrorHandling {
    require(ids.length <= 50, "The maximum number of IDs is 50")

    val req = requests.get(f"$endpoint/tracks",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("ids", ids.mkString(","))) ++ (if (market.nonEmpty) List(("market", market)) else Nil))

    val res = read[Map[String, List[TrackJson]]](req.text)
    Right(res("tracks").map(Track.fromJson))
  }

  /**
   * Gets Spotify catalog information for a single track identified by its unique Spotify ID.
   *
   * @param id     the Spotify ID for the track
   * @param market (optional) an ISO 3166-1 alpha-2 country code or the string from_token. Provide this parameter if
   *               you want to apply Track Relinking
   * @return a [[Track]] on success, otherwise it returns [[Error]]
   */
  def getTrack(id: String, market: String = ""): Either[Error, Track] = withErrorHandling {
    val req = requests.get(f"$endpoint/tracks/$id",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = if (market.nonEmpty) List(("market", market)) else Nil)

    val res = read[TrackJson](req.text)
    Right(Track.fromJson(res))
  }

  /**
   * Gets Spotify Catalog information about albums, artists, playlists, tracks, shows or episodes that match a
   * keyword string.
   *
   * @param q               search query keywords and optional field filters and operators.
   *
   *                        For example: roadhouse+blues.
   * @param objectTypes     a  list of [[ObjectType]] to search across.
   *                        Valid types are: [[AlbumObj]], [[ArtistObj]], [[TrackObj]], [[ShowObj]] and [[EpisodeObj]].
   *
   *                        Search results include hits from all the specified item types
   * @param market          (optional) an ISO 3166-1 alpha-2 country code or the string from_token.
   *                        If a country code is specified, only artists, albums, and tracks with content that is playable in
   *                        that market is returned.
   *
   *                        Note:
   *               - Playlist results are not affected by the market parameter.
   *               - If market is set to from_token, and a valid access token is specified in the request header,
   *                 only content playable in the country associated with the user account, is returned.
   *               - Users can view the country that is associated with their account in the account settings. A user
   *                 must grant access to the user-read-private scope prior to when the access token is issued
   * @param limit           (optional) maximum number of results to return.
   *                        Default: 20
   *                        Minimum: 1
   *                        Maximum: 50
   *
   *                        Note: The limit is applied within each type, not on the total response
   * @param offset          (optional) the index of the first result to return.
   *                        Default: 0 (the first result).
   *                        Maximum offset (including limit): 2,000.
   *
   *                        Use with limit to get the next page of search results
   * @param includeExternal (optional) possible values: audio
   *
   *                        If include_external=audio is specified the response will include any relevant audio content
   *                        that is hosted externally. By default external content is filtered out from responses
   * @return a List of [[Paging]] objects wrapping [[Searchable]]s on success, otherwise it returns [[Error]].
   *         [[Album]], [[Artist]], [[Episode]], [[Show]] and [[Track]] are searchable.
   */
  def search(q: String, objectTypes: List[ObjectType], market: String = "", limit: Int = 20, offset: Int = 0,
             includeExternal: String = ""): Either[Error, List[Paging[Searchable]]] = withErrorHandling {
    def requestObjectType(objectType: ObjectType): Paging[Searchable] = {
      val req = requests.get(f"$endpoint/search",
        headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
        params = List(("limit", limit.toString), ("offset", offset.toString), ("q", q.replace("%20", "+")), ("type", objectType.toString))
          ++ (if (market.nonEmpty) List(("market", market)) else Nil)
          ++ (if (includeExternal.nonEmpty) List(("include_external", includeExternal)) else Nil))

      objectType match {
        case AlbumObj =>
          val json = read[Map[String, Paging[AlbumJson]]](req.text)
          val res = json("albums")
          res.copy(items = res.items.map(_.map(Album.fromJson)))
        case ArtistObj =>
          val json = read[Map[String, Paging[ArtistJson]]](req.text)
          val res = json("artists")
          res.copy(items = res.items.map(_.map(Artist.fromJson)))
        case EpisodeObj =>
          val json = read[Map[String, Paging[EpisodeJson]]](req.text)
          val res = json("episodes")
          res.copy(items = res.items.map(_.map(Episode.fromJson)))
        case ShowObj =>
          val json = read[Map[String, Paging[ShowJson]]](req.text)
          val res = json("shows")
          res.copy(items = res.items.map(_.map(Show.fromJson)))
        case TrackObj =>
          val json = read[Map[String, Paging[TrackJson]]](req.text)
          val res = json("tracks")
          res.copy(items = res.items.map(_.map(Track.fromJson)))
      }
    }

    require(q.nonEmpty, "The q parameter must be non-empty")
    require(objectTypes.nonEmpty, "The objectTypes parameter must be non-empty")
    require(1 <= limit && limit <= 50, "The limit parameter must be between 1 and 50")
    require(0 <= offset && offset <= 2000, "The offset parameter must be between 0 and 2000")

    val pagings = objectTypes.map(obj => Future {
      requestObjectType(obj)
    }).map(fut => Await.result(fut, Duration.Inf))
    Right(pagings)
  }

  /**
   * Checks to see if the current user is following one or more artists or other Spotify users.
   *
   * Getting details of the artists or users the current user follows requires authorization of the user-follow-read
   * scope.
   *
   * @param idType the ID type: either artist or user
   * @param ids    a list of the artist or the user Spotify IDs to check. A maximum of 50 IDs can be sent in one request
   * @return a List of [[Boolean]]s on success (in the same order in which the IDs were specified),
   *         otherwise it returns [[Error]]
   */
  def isFollowing(idType: String, ids: List[String]): Either[Error, List[Boolean]] = withErrorHandling {
    require(idType == "artist" || idType == "user", "The ID type can be either 'artist' or 'user'")
    require(ids.nonEmpty, "At least one ID must be specified")
    require(ids.length <= 50, "The maximum number of IDs is 50")

    val req = requests.get(f"$endpoint/me/following/contains",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("type", idType), ("ids", ids.mkString(","))))

    val res = read[List[Boolean]](req.text)
    Right(res)
  }

  /**
   * Checks to see if one or more Spotify users are following a specified playlist.
   *
   * Following a playlist can be done publicly or privately.
   *
   * Checking if a user publicly follows a playlist doesn’t
   * require any scopes; if the user is publicly following the playlist, this endpoint returns true.
   *
   * Checking if the user is privately following a playlist is only possible for the current user when that
   * user has granted access to the playlist-read-private scope.
   *
   * @param playlistID the Spotify ID of the playlist
   * @param ids        a list of Spotify User IDs; the ids of the users that you want to check to see if they follow the
   *                   playlist. Maximum: 5 IDs
   * @return a List of [[Boolean]]s on success (in the same order in which the IDs were specified),
   *         otherwise it returns [[Error]]
   */
  def areUsersFollowingPlaylist(playlistID: String, ids: List[String]): Either[Error, List[Boolean]] = withErrorHandling {
    require(ids.nonEmpty, "At least one ID must be specified")
    require(ids.length <= 5, "The maximum number of IDs is 5")

    val req = requests.get(f"$endpoint/playlists/$playlistID/followers/contains",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("ids", ids.mkString(","))))

    val res = read[List[Boolean]](req.text)
    Right(res)
  }

  /**
   * Adds the current user as a follower of one or more artists or other Spotify users.
   *
   * Modifying the list of artists or users the current user follows requires authorization of the user-follow-modify
   * scope.
   *
   * @param idType the ID type: either artist or user
   * @param ids    a list of the artist or the user Spotify IDs. A maximum of 50 IDs can be sent in one request
   * @return [[Unit]] on success, otherwise it returns [[Error]]
   */
  def follow(idType: String, ids: List[String]): Either[Error, Unit] = withErrorHandling {
    require(idType == "artist" || idType == "user", "The ID type can be either 'artist' or 'user'")
    require(ids.nonEmpty, "At least one ID must be specified")
    require(ids.length <= 50, "The maximum number of IDs is 50")

    val req = requests.put(f"$endpoint/me/following",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("type", idType), ("ids", ids.mkString(","))))

    if (req.statusCode != 204) Left(read[Error](req.text))
    else Right(())
  }

  /**
   * Adds the current user as a follower of a playlist.
   *
   * Following a playlist publicly requires authorization of the playlist-modify-public scope; following it privately
   * requires the playlist-modify-private scope.
   *
   * @param playlistID the Spotify ID of the playlist. Any playlist can be followed, regardless of its public/private
   *                   status, as long as you know its playlist ID
   * @param public     (optional) defaults to true. If true the playlist will be included in user’s public playlists,
   *                   if false it will remain private. To be able to follow playlists privately, the user must have
   *                   granted the playlist-modify-private scope
   * @return [[Unit]] on success, otherwise it returns [[Error]]
   */
  def followPlaylist(playlistID: String, public: Boolean = true): Either[Error, Unit] = withErrorHandling {
    val req = requests.put(f"$endpoint/playlists/$playlistID/followers",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}"), ("Content-Type", "application/json")),
      data = List(("public", public.toString)))

    if (req.statusCode != 200) Left(read[Error](req.text))
    else Right(())
  }

  /**
   * Gets the current user’s followed artists.
   *
   * Getting details of the artists or users the current user follows requires authorization of the user-follow-read
   * scope.
   *
   * @param limit (optional) the maximum number of items to return. Default: 20. Minimum: 1. Maximum: 50
   * @param after (optional) the last artist ID retrieved from the previous request
   * @return a [[CursorPaging]] object wrapping [[Artist]]s on success, otherwise it returns [[Error]]
   */
  def getFollowedArtists(limit: Int = 20, after: String = ""): Either[Error, CursorPaging[Artist]] = withErrorHandling {
    require(1 <= limit && limit <= 50, "The limit parameter must be between 1 and 50")

    val req = requests.get(f"$endpoint/me/following",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("limit", limit.toString), ("type", "artist"))
        ++ (if (after.nonEmpty) List(("after", after)) else Nil))

    val res = {
      val json = read[Map[String, CursorPaging[ArtistJson]]](req.text)
      json("artists")
    }
    Right(res.copy(items = res.items.map(_.map(Artist.fromJson))))
  }

  /**
   * Removes the current user as a follower of one or more artists or other Spotify users.
   *
   * Modifying the list of artists or users the current user follows requires authorization of the user-follow-modify
   * scope.
   *
   * @param idType the ID type: either artist or user
   * @param ids    a list of the artist or the user Spotify IDs. A maximum of 50 IDs can be sent in one request
   * @return [[Unit]] on success, otherwise it returns [[Error]]
   */
  def unfollow(idType: String, ids: List[String]): Either[Error, Unit] = withErrorHandling {
    require(idType == "artist" || idType == "user", "The ID type can be either 'artist' or 'user'")
    require(ids.nonEmpty, "At least one ID must be specified")
    require(ids.length <= 50, "The maximum number of IDs is 50")

    val req = requests.delete(f"$endpoint/me/following",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("type", idType), ("ids", ids.mkString(","))))

    if (req.statusCode != 204) Left(read[Error](req.text))
    else Right(())
  }

  /**
   * Removes the current user as a follower of a playlist.
   *
   * Unfollowing a publicly followed playlist for a user requires authorization of the playlist-modify-public scope;
   * unfollowing a privately followed playlist requires the playlist-modify-private scope.
   *
   * @param playlistID the Spotify ID of the playlist that is to be no longer followed
   * @return [[Unit]] on success, otherwise it returns [[Error]]
   */
  def unfollowPlaylist(playlistID: String): Either[Error, Unit] = withErrorHandling {
    val req = requests.delete(f"$endpoint/playlists/$playlistID/followers",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")))

    if (req.statusCode != 200) Left(read[Error](req.text))
    else Right(())
  }

  /**
   * Checks if one or more albums is already saved in the current Spotify user’s ‘Your Music’ library.
   *
   * The user-library-read scope must have been authorized by the user.
   *
   * @param ids a list of the Spotify IDs for the albums. Maximum: 50 IDs
   * @return a List of [[Boolean]]s on success (in the same order in which the IDs were specified),
   *         otherwise it returns [[Error]]
   */
  def areAlbumsSaved(ids: List[String]): Either[Error, List[Boolean]] = withErrorHandling {
    require(ids.nonEmpty, "At least one ID must be specified")
    require(ids.length <= 50, "The maximum number of IDs is 50")

    val req = requests.get(f"$endpoint/me/albums/contains",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("ids", ids.mkString(","))))

    val res = read[List[Boolean]](req.text)
    Right(res)
  }

  /**
   * Checks if one or more shows is already saved in the current Spotify user’s library.
   *
   * The user-library-read scope must have been authorized by the user.
   *
   * @param ids a list of the Spotify IDs for the shows. Maximum: 50 IDs
   * @return a List of [[Boolean]]s on success (in the same order in which the IDs were specified),
   *         otherwise it returns [[Error]]
   */
  def areShowsSaved(ids: List[String]): Either[Error, List[Boolean]] = withErrorHandling {
    require(ids.nonEmpty, "At least one ID must be specified")
    require(ids.length <= 50, "The maximum number of IDs is 50")

    val req = requests.get(f"$endpoint/me/shows/contains",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("ids", ids.mkString(","))))

    val res = read[List[Boolean]](req.text)
    Right(res)
  }

  /**
   * Checks if one or more tracks is already saved in the current Spotify user’s library.
   *
   * The user-library-read scope must have been authorized by the user.
   *
   * @param ids a list of the Spotify IDs for the tracks. Maximum: 50 IDs
   * @return a List of [[Boolean]]s on success (in the same order in which the IDs were specified),
   *         otherwise it returns [[Error]]
   */
  def areTracksSaved(ids: List[String]): Either[Error, List[Boolean]] = withErrorHandling {
    require(ids.nonEmpty, "At least one ID must be specified")
    require(ids.length <= 50, "The maximum number of IDs is 50")

    val req = requests.get(f"$endpoint/me/tracks/contains",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("ids", ids.mkString(","))))

    val res = read[List[Boolean]](req.text)
    Right(res)
  }

  /**
   * Gets a list of the albums saved in the current Spotify user’s ‘Your Music’ library.
   *
   * The user-library-read scope must have been authorized by the user.
   *
   * @param limit  (optional) the maximum number of objects to return. Default: 20. Minimum: 1. Maximum: 50
   * @param offset (optional) the index of the first object to return. Default: 0 (i.e., the first object). Use with limit to get
   *               the next set of objects
   * @param market (optional) an ISO 3166-1 alpha-2 country code or the string from_token. Provide this parameter if you want to
   *               apply Track Relinking
   * @return a [[Paging]] object wrapping [[SavedAlbum]]s on success, otherwise it returns [[Error]]
   */
  def getSavedAlbums(limit: Int = 20, offset: Int = 0, market: String = ""): Either[Error, Paging[SavedAlbum]] =
    withErrorHandling {
      require(1 <= limit && limit <= 50, "The limit parameter must be between 1 and 50")
      require(0 <= offset, "The offset parameter must be non-negative")

      val req = requests.get(f"$endpoint/me/albums",
        headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
        params = List(("limit", limit.toString), ("offset", offset.toString))
          ++ (if (market.nonEmpty) List(("market", market)) else Nil))

      val res = read[Paging[SavedAlbumJson]](req.text)
      Right(res.copy(items = res.items.map(_.map(SavedAlbum.fromJson))))
    }

  /**
   * Get a list of shows saved in the current Spotify user’s library. Optional parameters can be used to limit the
   * number of shows returned.
   *
   * The user-library-read scope must have been authorized by the user.
   *
   * If the current user has no shows saved, the response will be an empty array. If a show is unavailable in the given
   * market it is filtered out. The total field in the paging object represents the number of all items, filtered or
   * not, and thus might be larger than the actual total number of observable items.
   *
   * @param limit  (optional) the maximum number of shows to return. Default: 20. Minimum: 1. Maximum: 50
   * @param offset (optional) the index of the first show to return. Default: 0 (i.e., the first object). Use with limit to get
   *               the next set of shows
   * @param market (optional) an ISO 3166-1 alpha-2 country code or the string from_token. Provide this parameter if you want to
   *               apply Track Relinking
   * @return a [[Paging]] object wrapping [[SavedShow]]s on success, otherwise it returns [[Error]]
   */
  def getSavedShows(limit: Int = 20, offset: Int = 0, market: String = ""): Either[Error, Paging[SavedShow]] =
    withErrorHandling {
      require(1 <= limit && limit <= 50, "The limit parameter must be between 1 and 50")
      require(0 <= offset, "The offset parameter must be non-negative")

      val req = requests.get(f"$endpoint/me/shows",
        headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
        params = List(("limit", limit.toString), ("offset", offset.toString))
          ++ (if (market.nonEmpty) List(("market", market)) else Nil))

      val res = read[Paging[SavedShowJson]](req.text)
      Right(res.copy(items = res.items.map(_.map(SavedShow.fromJson))))
    }

  /**
   * Get a list of shows saved in the current Spotify user’s library. Optional parameters can be used to limit the
   * number of shows returned.
   *
   * The user-library-read scope must have been authorized by the user.
   *
   * @param limit  (optional) the maximum number of objects to return. Default: 20. Minimum: 1. Maximum: 50
   * @param offset (optional) the index of the first object to return. Default: 0 (i.e., the first object). Use with limit to get
   *               the next set of objects
   * @param market (optional) an ISO 3166-1 alpha-2 country code or the string from_token. Provide this parameter if you want to
   *               apply Track Relinking
   * @return a [[Paging]] object wrapping [[SavedTrack]]s on success, otherwise it returns [[Error]]
   */
  def getSavedTracks(limit: Int = 20, offset: Int = 0, market: String = ""): Either[Error, Paging[SavedTrack]] =
    withErrorHandling {
      require(1 <= limit && limit <= 50, "The limit parameter must be between 1 and 50")
      require(0 <= offset, "The offset parameter must be non-negative")

      val req = requests.get(f"$endpoint/me/tracks",
        headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
        params = List(("limit", limit.toString), ("offset", offset.toString))
          ++ (if (market.nonEmpty) List(("market", market)) else Nil))

      val res = read[Paging[SavedTrackJson]](req.text)
      Right(res.copy(items = res.items.map(_.map(SavedTrack.fromJson))))
    }

  /**
   * Removes one or more albums from the current user’s ‘Your Music’ library.
   *
   * Modification of the current user’s “Your Music” collection requires authorization of the user-library-modify scope.
   *
   * N.B. Changes to a user’s saved albums may not be visible in other Spotify applications immediately.
   *
   * @param ids a list of the Spotify IDs for the albums. Maximum: 50 IDs.
   * @return [[Unit]] on success, otherwise it returns [[Error]]
   */
  def removeSavedAlbums(ids: List[String]): Either[Error, Unit] = withErrorHandling {
    require(ids.nonEmpty, "At least one ID must be specified")
    require(ids.length <= 50, "The maximum number of IDs is 50")

    val req = requests.delete(f"$endpoint/me/albums",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("ids", ids.mkString(","))))

    if (req.statusCode != 200) Left(read[Error](req.text))
    else Right(())
  }

  /**
   * Removes one or more shows from the current user’s ‘Your Music’ library.
   *
   * The user-library-modify scope must have been authorized by the user.
   *
   * N.B. Changes to a user’s saved shows may not be visible in other Spotify applications immediately.
   *
   * @param ids    a list of the Spotify IDs for the shows to be deleted from the user’s library. Maximum: 50 IDs
   * @param market an ISO 3166-1 alpha-2 country code. If a country code is specified, only shows that are available in
   *               that market will be removed.
   *
   *               If a valid user access token is specified in the request header, the country associated with the
   *               user account will take priority over this parameter.
   *
   *               Note: If neither market or user country are provided, the content is considered unavailable for
   *               the client.
   *
   *               Users can view the country that is associated with their account in the account settings.
   * @return [[Unit]] on success, otherwise it returns [[Error]]
   */
  def removeSavedShows(ids: List[String], market: String = ""): Either[Error, Unit] = withErrorHandling {
    require(ids.nonEmpty, "At least one ID must be specified")
    require(ids.length <= 50, "The maximum number of IDs is 50")

    val req = requests.delete(f"$endpoint/me/shows",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("ids", ids.mkString(","))) ++ (if (market.nonEmpty) List(("market", market)) else Nil))

    if (req.statusCode != 200) Left(read[Error](req.text))
    else Right(())
  }

  /**
   * Removes one or more tracks from the current user’s ‘Your Music’ library.
   *
   * Modification of the current user’s “Your Music” collection requires authorization of the user-library-modify scope.
   *
   * N.B. Changes to a user’s saved tracks may not be visible in other Spotify applications immediately.
   *
   * @param ids a list of the Spotify IDs for the tracks. Maximum: 50 IDs
   * @return [[Unit]] on success, otherwise it returns [[Error]]
   */
  def removeSavedTracks(ids: List[String]): Either[Error, Unit] = withErrorHandling {
    require(ids.nonEmpty, "At least one ID must be specified")
    require(ids.length <= 50, "The maximum number of IDs is 50")

    val req = requests.delete(f"$endpoint/me/tracks",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("ids", ids.mkString(","))))

    if (req.statusCode != 200) Left(read[Error](req.text))
    else Right(())
  }

  /**
   * Saves one or more albums to the current user’s ‘Your Music’ library.
   *
   * Modification of the current user’s “Your Music” collection requires authorization of the user-library-modify scope.
   *
   * @param ids a list of the Spotify IDs for the albums. Maximum: 50 IDs
   * @return [[Unit]] on success, otherwise it returns [[Error]]
   */
  def saveAlbums(ids: List[String]): Either[Error, Unit] = withErrorHandling {
    require(ids.nonEmpty, "At least one ID must be specified")
    require(ids.length <= 50, "The maximum number of IDs is 50")

    val req = requests.put(f"$endpoint/me/albums",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("ids", ids.mkString(","))))

    if (req.statusCode != 200) Left(read[Error](req.text))
    else Right(())
  }

  /**
   * Saves one or more shows to the current user’s ‘Your Music’ library.
   *
   * The user-library-modify scope must have been authorized by the user.
   *
   * @param ids a list of the Spotify IDs for the shows to be added to the user’s library. Maximum: 50 IDs
   * @return [[Unit]] on success, otherwise it returns [[Error]]
   */
  def saveShows(ids: List[String]): Either[Error, Unit] = withErrorHandling {
    require(ids.nonEmpty, "At least one ID must be specified")
    require(ids.length <= 50, "The maximum number of IDs is 50")

    val req = requests.put(f"$endpoint/me/shows",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("ids", ids.mkString(","))))

    if (req.statusCode != 200) Left(read[Error](req.text))
    else Right(())
  }

  /**
   * Saves one or more tracks to the current user’s ‘Your Music’ library.
   *
   * Modification of the current user’s “Your Music” collection requires authorization of the user-library-modify scope.
   *
   * @param ids a list of the Spotify IDs for the tracks. Maximum: 50 IDs
   * @return [[Unit]] on success, otherwise it returns [[Error]]
   */
  def saveTracks(ids: List[String]): Either[Error, Unit] = withErrorHandling {
    require(ids.nonEmpty, "At least one ID must be specified")
    require(ids.length <= 50, "The maximum number of IDs is 50")

    val req = requests.put(f"$endpoint/me/tracks",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("ids", ids.mkString(","))))

    if (req.statusCode != 200) Left(read[Error](req.text))
    else Right(())
  }

  /**
   * Gets detailed profile information about the current user (including the current user’s username).
   *
   * Reading the user’s email address requires the user-read-email scope; reading country and product subscription
   * level requires the user-read-private scope.
   *
   * N.B. If the user-read-email scope is authorized, the returned [[User]] will include the email address that was
   * entered when the user created their Spotify account. This email address is unverified; do not assume that the
   * email address belongs to the user.
   *
   * @return a [[User]] on success, otherwise it returns [[Error]]
   */
  def getCurrentUserProfile: Either[Error, User] = withErrorHandling {
    val req = requests.get(f"$endpoint/me", headers = List(("Authorization", f"Bearer ${authObj.accessToken}")))
    Right(User.fromJson(read[UserJson](req.text)))
  }

  private def withErrorHandling[T](task: => Either[Error, T]): Either[Error, T] = {
    try {
      task
    } catch {
      case e: RequestFailedException => Left(read[Error](e.response.text))
    }
  }

  /**
   * Gets public profile information about a Spotify user.
   *
   * @param userID the user’s Spotify user ID
   * @return a [[User]] on success, otherwise it returns [[Error]]
   */
  def getUserProfile(userID: String): Either[Error, User] = withErrorHandling {
    val req = requests.get(f"$endpoint/users/$userID",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")))
    Right(User.fromJson(read[UserJson](req.text)))
  }

  private case class FeaturedPlaylistsAnswer(message: String, playlists: Paging[PlaylistJson])

  private object FeaturedPlaylistsAnswer { // actually used
    implicit val rw: ReadWriter[FeaturedPlaylistsAnswer] = macroRW
  }

}

object Spotify {
  def apply(authFlow: AuthFlow): Spotify = new Spotify(authFlow)

  def apply(clientID: String, clientSecret: String): Spotify = new Spotify(clientID, clientSecret)

  def apply(clientID: String, clientSecret: String, redirectURI: URI, scopes: List[String] = Nil, withPKCE: Boolean = false): Spotify =
    new Spotify(clientID, clientSecret, redirectURI, scopes, withPKCE)
}


