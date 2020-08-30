package com.github.gondolav.spotify4s

import com.github.gondolav.spotify4s.auth.{AuthException, AuthFlow, AuthObj}
import com.github.gondolav.spotify4s.entities.{Album, AlbumJson, Artist, ArtistJson, Error, Paging, Track, TrackJson}
import requests.RequestFailedException
import upickle.default._

class Spotify(authFlow: AuthFlow) {
  val authObj: AuthObj = authFlow.authenticate match {
    case Left(error) => throw new AuthException(f"An error occurred while authenticating: '${error.errorDescription}'\n", error)
    case Right(value) => value
  }
  private val endpoint = "https://api.spotify.com/v1"

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
    Right(res.copy(items = res.items.map(Track.fromJson)))
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

  private def withErrorHandling[T](task: => Right[Nothing, T]): Either[Error, T] = {
    try {
      task
    } catch {
      case e: RequestFailedException => Left(read[Error](e.response.text))
    }
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
    Right(res.copy(items = res.items.map(Album.fromJson)))
  }

  /**
   * Gets Spotify catalog information about an artist’s top tracks by country.
   *
   * @param id      the Spotify ID for the artist
   * @param country an ISO 3166-1 alpha-2 country code or the string from_token.
   * @return a List of up to 10 [[Track]]s on success, otherwise it returns [[Error]]
   */
  def getArtistTopTracks(id: String, country: String): Either[Error, List[Track]] = {
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
  def getArtistRelatedArtists(id: String): Either[Error, List[Artist]] = {
    val req = requests.get(f"$endpoint/artists/$id/related-artists", headers = List(("Authorization", f"Bearer ${authObj.accessToken}")))

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
  def getArtists(ids: List[String]): Either[Error, List[Artist]] = {
    require(ids.length <= 50, "The maximum number of IDs is 50")

    val req = requests.get(f"$endpoint/artists",
      headers = List(("Authorization", f"Bearer ${authObj.accessToken}")),
      params = List(("ids", ids.mkString(","))))

    val res = read[Map[String, List[ArtistJson]]](req.text)
    Right(res("artists").map(Artist.fromJson))
  }
}

object Spotify {
  def apply(authFlow: AuthFlow): Spotify = new Spotify(authFlow)
}


